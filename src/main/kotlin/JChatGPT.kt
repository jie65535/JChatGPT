package top.jie65535.mirai

import net.mamoe.mirai.console.command.CommandManager.INSTANCE.register
import net.mamoe.mirai.console.command.CommandSender.Companion.toCommandSender
import net.mamoe.mirai.console.permission.PermissionId
import net.mamoe.mirai.console.permission.PermissionService
import net.mamoe.mirai.console.permission.PermissionService.Companion.hasPermission
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.contact.isOperator
import net.mamoe.mirai.event.EventPriority
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.FriendMessageEvent
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.event.events.MessagePostSendEvent
import net.mamoe.mirai.event.events.MessageRecallEvent
import net.mamoe.mirai.message.data.At
import net.mamoe.mirai.message.data.Message
import net.mamoe.mirai.message.data.QuoteReply
import net.mamoe.mirai.message.data.content
import net.mamoe.mirai.utils.info
import top.jie65535.mirai.command.PluginCommands
import top.jie65535.mirai.config.PluginConfig
import top.jie65535.mirai.conversation.ConversationContext
import top.jie65535.mirai.conversation.ConversationEngine
import top.jie65535.mirai.data.ChatHistoryStore
import top.jie65535.mirai.data.ChatMessageRecord
import top.jie65535.mirai.data.ContactSnapshotRefresher
import top.jie65535.mirai.data.ContactSnapshotStore
import top.jie65535.mirai.data.PluginData
import top.jie65535.mirai.data.SkillStore
import top.jie65535.mirai.data.TokenUsageStore
import top.jie65535.mirai.llm.LargeLanguageModels
import top.jie65535.mirai.profile.ProfileAutoMaintenance
import top.jie65535.mirai.profile.UserProfileStore
import kotlin.random.Random

object JChatGPT : KotlinPlugin(
    JvmPluginDescription(
        id = "top.jie65535.mirai.JChatGPT",
        name = "J ChatGPT",
        version = "1.15.0",
    ) {
        author("jie65535")
    }
) {
    internal var includeHistory: Boolean = false

    val chatPermission = PermissionId("JChatGPT", "Chat")

    private var keyword: Regex? = null

    override fun onEnable() {
        PermissionService.INSTANCE.register(chatPermission, "JChatGPT Chat Permission")
        PluginConfig.reload()
        PluginData.reload()
        TokenUsageStore.init(dataFolder)
        SkillStore.init(dataFolder)

        includeHistory = try {
            ChatHistoryStore.init(dataFolder) { message, cause ->
                if (cause == null) logger.warning(message) else logger.warning(message, cause)
            }
            true
        } catch (cause: Throwable) {
            logger.error("初始化 SQLite 聊天记录失败，历史上下文与搜索将暂时禁用", cause)
            false
        }
        if (includeHistory) {
            runCatching { ContactSnapshotStore.init(dataFolder) }
                .onFailure { logger.error("初始化联系人快照数据库失败，联系人画像辅助将暂时禁用", it) }
        }

        runCatching { UserProfileStore.init(dataFolder) }
            .onFailure { logger.error("初始化用户画像数据库失败，画像分析将暂时禁用", it) }

        LargeLanguageModels.reload()
        PluginCommands.register()
        keyword = PluginConfig.callKeyword.takeIf(String::isNotEmpty)?.let(::Regex)

        val eventChannel = GlobalEventChannel.parentScope(this)
        eventChannel.subscribeAlways<MessageEvent>(priority = EventPriority.HIGHEST) { event ->
            ContactSnapshotRefresher.schedule(event.bot, "message")
            runCatching { ChatHistoryStore.record(event) }
                .onFailure { logger.warning("保存接收消息到 SQLite 失败", it) }
        }
        eventChannel.subscribeAlways<MessagePostSendEvent<*>>(priority = EventPriority.HIGHEST) { event ->
            runCatching { ChatHistoryStore.record(event) }
                .onFailure { logger.warning("保存发送消息到 SQLite 失败", it) }
        }
        eventChannel.subscribeAlways<MessageRecallEvent>(priority = EventPriority.HIGHEST) { event ->
            runCatching { ChatHistoryStore.markRecalled(event) }
                .onFailure { logger.warning("更新 SQLite 消息撤回状态失败", it) }
        }
        eventChannel.subscribeAlways<MessageEvent> { event -> onMessage(event) }
        ContactSnapshotRefresher.scheduleAll("startup")

        logger.info { "Plugin loaded" }
    }

    override fun onDisable() {
        ConversationEngine.clear()
        ConversationContext.clearAll()
        ProfileAutoMaintenance.clear()
        ContactSnapshotRefresher.clear()
        UserProfileStore.close()
        ContactSnapshotStore.close()
        ChatHistoryStore.close()
    }

    fun clearContextCache() {
        ConversationContext.clearCache()
    }

    internal fun lookupReplyTarget(subjectId: Long, index: Int): ChatMessageRecord? =
        ConversationContext.lookupReplyTarget(subjectId, index)

    internal fun registerImage(subjectId: Long, imageId: String, imageUrl: String): Int? =
        ConversationContext.registerImage(subjectId, imageId, imageUrl)

    internal fun lookupImageUrl(subjectId: Long, index: Int): String? =
        ConversationContext.lookupImageUrl(subjectId, index)

    fun toMessage(contact: Contact, content: String): Message =
        ConversationContext.toMessage(contact, content)

    private suspend fun onMessage(event: MessageEvent) {
        if (LargeLanguageModels.chat == null) return
        if (!event.toCommandSender().hasPermission(chatPermission)) {
            if (event is GroupMessageEvent) {
                if (!PluginConfig.groupOpHasChatPermission || !event.sender.isOperator()) {
                    if (event.sender.active.temperature < PluginConfig.temperaturePermission) return
                }
            }
            if (event is FriendMessageEvent && !PluginConfig.friendHasChatPermission) return
        }

        val triggered = event.message.contains(At(event.bot)) ||
                keyword?.containsMatchIn(event.message.content) == true ||
                event.message[QuoteReply]?.source?.fromId == event.bot.id
        if (!triggered) return

        if (event is GroupMessageEvent && event.group.botMuteRemaining > 0) {
            logger.info(
                "bot 在群 ${event.group.name}(${event.group.id}) 被禁言，" +
                        "剩余 ${event.group.botMuteRemaining} 秒，忽略消息"
            )
            return
        }

        if (PluginConfig.enableFavorabilitySystem && shouldIgnoreForFavorability(event)) return
        ConversationEngine.start(event)
    }

    private suspend fun shouldIgnoreForFavorability(event: MessageEvent): Boolean {
        val info = PluginData.userFavorability[event.sender.id] ?: return false
        if (info.value >= 0) return false
        val probability = kotlin.math.abs(info.value).toDouble() / 100.0
        if (Random.nextDouble() >= probability) return false
        logger.info(
            "根据好感度系统，用户 ${event.senderName}(${event.sender.id}) " +
                    "(好感度: ${info.value}) 的消息被忽略，忽略概率: ${probability * 100}%"
        )
        event.subject.sendMessage("[实验功能] 因好感度低，此消息已被忽略(${probability * 100}%)")
        return true
    }

}
