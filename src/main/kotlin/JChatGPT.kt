package top.jie65535.mirai

import com.aallam.openai.api.chat.ChatCompletionChunk
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.core.Usage
import com.aallam.openai.api.model.ModelId
import io.ktor.util.collections.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.register
import net.mamoe.mirai.console.command.CommandSender.Companion.toCommandSender
import net.mamoe.mirai.console.permission.PermissionId
import net.mamoe.mirai.console.permission.PermissionService
import net.mamoe.mirai.console.permission.PermissionService.Companion.hasPermission
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.contact.MemberPermission.*
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.FriendMessageEvent
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.message.data.Image.Key.queryUrl
import net.mamoe.mirai.utils.info
import top.jie65535.mirai.tools.*
import util.LunarDateUtil
import xyz.cssxsh.mirai.hibernate.MiraiHibernateRecorder
import xyz.cssxsh.mirai.hibernate.entry.MessageRecord
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.collections.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

object JChatGPT : KotlinPlugin(
    JvmPluginDescription(
        id = "top.jie65535.mirai.JChatGPT",
        name = "J ChatGPT",
        version = "1.12.0",
    ) {
        author("jie65535")
//        dependsOn("xyz.cssxsh.mirai.plugin.mirai-hibernate-plugin", true)
    }
) {
    /**
     * 是否包含历史对话
     */
    internal var includeHistory: Boolean = false

    /**
     * 聊天权限
     */
    val chatPermission = PermissionId("JChatGPT", "Chat")

    /**
     * 唤醒关键字
     */
    private var keyword: Regex? = null

    override fun onEnable() {
        // 注册聊天权限
        PermissionService.INSTANCE.register(chatPermission, "JChatGPT Chat Permission")
        PluginConfig.reload()
        PluginData.reload()

        // 初始化 token 使用日聚合存储（独立 JSON 文件，绕开 yamlkt 大数据 bug）
        TokenUsageStore.init(dataFolder)

        // 初始化技能存储（data/skills/ 下的 markdown 文件，全局跨群）
        SkillStore.init(dataFolder)

        // 设置Token
        LargeLanguageModels.reload()

        // 注册插件命令
        PluginCommands.register()

        // 检查消息记录插件是否存在
        includeHistory = try {
            MiraiHibernateRecorder
            true
        } catch (_: Throwable) {
            false
        }

        if (PluginConfig.callKeyword.isNotEmpty()) {
            keyword = Regex(PluginConfig.callKeyword)
        }

        GlobalEventChannel.parentScope(this)
            .subscribeAlways<MessageEvent> { event -> onMessage(event) }

        // 启动定时任务处理好感度时间偏移
        if (PluginConfig.enableFavorabilitySystem) {
            launch {
                while (true) {
                    delay(24.hours) // 每24小时执行一次
                    shiftFavorabilityOverTime()
                }
            }
        }

        logger.info { "Plugin loaded" }
    }

    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年MM月dd E HH:mm:ss")

    private val requestMap = ConcurrentSet<Long>()

    /**
     * 对话上下文缓存
     */
    private val contextCache = ConcurrentMap<Long, ConversationCache>()

    /**
     * 清空所有对话上下文缓存（供管理员命令使用）
     */
    fun clearContextCache() {
        contextCache.clear()
    }

    /**
     * 对话上下文缓存数据类
     * @param history 完整的消息历史
     * @param lastActivityAt 最后活动时间戳
     */
    private data class ConversationCache(
        val history: MutableList<ChatMessage>,
        val lastActivityAt: Int,
        val replyIndex: ReplyIndex
    ) {
        fun isExpired(ttlSeconds: Int): Boolean {
            return OffsetDateTime.now().toEpochSecond().toInt() - lastActivityAt > ttlSeconds
        }
    }

    /**
     * 回复索引：每个会话(subject)在一次对话期间维护一份「短编号 -> 消息记录」映射，
     * 让 LLM 能用历史里每行行首的 [n] 来引用回复某条消息。
     * 编号按消息出现顺序递增，跨「初始历史」与「新增消息」连续编号；同一条消息(ids 相同)复用既有编号。
     */
    class ReplyIndex {
        private val byIndex = LinkedHashMap<Int, MessageRecord>()
        private val indexByIds = HashMap<String, Int>()
        private var counter = 0

        fun add(record: MessageRecord): Int {
            // ids 可能为 null（如发送失败的记录），此时无法去重/被引用匹配，但仍分配编号
            val ids = record.ids
            if (ids != null) {
                indexByIds[ids]?.let { return it }
            }
            val i = ++counter
            byIndex[i] = record
            if (ids != null) {
                indexByIds[ids] = i
            }
            return i
        }

        fun get(index: Int): MessageRecord? = byIndex[index]
        fun indexOfIds(ids: String): Int? = indexByIds[ids]
    }

    /** 各会话的回复索引，startChat 开始时重建，结束时清理 */
    private val replyIndexMap = ConcurrentMap<Long, ReplyIndex>()

    /** 供发言工具按编号查找被引用的历史消息 */
    internal fun lookupReplyTarget(subjectId: Long, index: Int): MessageRecord? =
        replyIndexMap[subjectId]?.get(index)

    private val shortTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneOffset.systemDefault())

    // 同一发言者连续消息默认省略时间以节省上下文；但间隔超过此阈值（秒）时仍补回时间，
    // 避免模型把刚发的续行消息误判为很久以前发生。
    private const val CONTINUATION_TIME_GAP_SECONDS = 60L

    private suspend fun onMessage(event: MessageEvent) {
        // 检查Token是否设置
        if (LargeLanguageModels.chat == null) return
        // 如果bot在群里被禁言，则无法发言，直接结束，避免浪费token
        if (event is GroupMessageEvent && event.group.botMuteRemaining > 0) {
            logger.info("bot 在群 ${event.group.name}(${event.group.id}) 被禁言，剩余 ${event.group.botMuteRemaining} 秒，忽略消息")
            return
        }
        // 发送者是否有权限
        if (!event.toCommandSender().hasPermission(chatPermission)) {
            if (event is GroupMessageEvent) {
                if (PluginConfig.groupOpHasChatPermission && event.sender.isOperator()) {
                    // 允许管理员使用
                } else if (event.sender.active.temperature >= PluginConfig.temperaturePermission) {
                    // 允许活跃度达标成员使用
                } else {
                    // 其它情况阻止使用
                    return
                }
            }
            if (event is FriendMessageEvent) {
                if (!PluginConfig.friendHasChatPermission) {
                    return
                }
                // TODO 检查好友上下文
            }
        }

        // 如果没有 @bot 或者 触发关键字 或者 回复bot的消息 则直接结束
        if (!event.message.contains(At(event.bot))
            && keyword?.let { event.message.content.contains(it) } != true
            && event.message[QuoteReply]?.source?.fromId != event.bot.id
        )
            return

        // 好感度系统检查
        if (PluginConfig.enableFavorabilitySystem) {
            val userId = event.sender.id
            PluginData.userFavorability[userId]?.let { favorabilityInfo ->
                val favorability = favorabilityInfo.value
                if (favorability < 0) {
                    // 负好感度有一定概率不回复
                    val probability = kotlin.math.abs(favorability).toDouble() / 100.0
                    if (kotlin.random.Random.nextDouble() < probability) {
                        // 不回复此消息
                        logger.info("根据好感度系统，用户 ${event.senderName}($userId) (好感度: $favorability) 的消息被忽略，忽略概率: ${probability * 100}%")
                        event.subject.sendMessage("[实验功能] 因好感度低，此消息已被忽略(${probability * 100}%)")
                        return
                    }
                }
            }
        }

        startChat(event)
    }

    private var memePrompt: String? = null

    private fun getSystemPrompt(event: MessageEvent): String {
        val now = OffsetDateTime.now()
        val prompt = StringBuilder(LargeLanguageModels.systemPrompt)
        fun replace(target: String, replacement: () -> String) {
            val i = prompt.indexOf(target)
            if (i != -1) {
                prompt.replace(i, i + target.length, replacement())
            }
        }

        replace("{time}") {
            val solarTime = dateTimeFormatter.format(now)
            val lunarInfo = LunarDateUtil.getFormattedLunarAndHoliday(now)
            "$solarTime\n农历$lunarInfo"
        }

        replace("{subject}") {
            if (event is GroupMessageEvent) {
                "\"${event.subject.name}\" 群聊中，你在本群的名片是：${getNameCard(event.subject.botAsMember)}"
            } else {
                "与 \"${event.senderName}\" 私聊中"
            }
        }

        replace("{memory}") {
            val memoryText = PluginData.contactMemory[event.subject.id]
            if (memoryText.isNullOrEmpty()) {
                "暂无相关记忆"
            } else memoryText
        }

        replace("{skills}") {
            if (PluginConfig.skillsEnabled) {
                SkillStore.buildIndexPrompt()
            } else "暂无技能"
        }

        replace("{meme}") {
            memePrompt?.let { return@replace it }

            if (PluginConfig.memeDir.isEmpty()) {
                ""
            } else {
                buildString {
                    val dir = File(PluginConfig.memeDir)
                    if (dir.exists() && dir.isDirectory) {
                        append("memes文件夹地址为：")
                        append(PluginConfig.memeDir)
                        appendLine()

                        val memes = dir.list()

                        if (memes.isEmpty()) {
                            append("暂无表情包~")
                        } else {
                            for (name in memes) {
                                append("- ")
                                append(name)
                                appendLine()
                            }
                            appendLine()
                            append("表情包示例：![")
                            append(memes[0])
                            append("](")
                            append(File(dir, memes[0]).absoluteFile)
                            append(")")
                            appendLine()
                        }
                    } else {
                        append("配置的meme路径不存在！")
                    }
                }.also {
                    memePrompt = it
                }
            }
        }

        return prompt.toString()
    }

    // region - 历史消息相关 -

    /**
     * 获取历史消息
     * @param event 消息事件
     * @return 如果未获取到则返回空字符串
     */
    private fun getHistory(event: MessageEvent): String {
        if (!includeHistory) {
            return event.message.content
        }
        val now = OffsetDateTime.now()
        // 一段时间内的消息
        val beforeTimestamp = now.minusMinutes(PluginConfig.historyWindowMin.toLong()).toEpochSecond().toInt()
        return getAfterHistory(beforeTimestamp, event)
    }

    /**
     * 获取指定时间后的历史消息
     * @param time Epoch时间戳
     * @param event 消息事件
     * @return 如果未获取到则返回空字符串
     */
    private fun getAfterHistory(time: Int, event: MessageEvent): String {
        if (!includeHistory) {
            return ""
        }
        // 现在时间
        val nowTimestamp = OffsetDateTime.now().toEpochSecond().toInt()
        // 最近这段时间的历史对话
        val history = MiraiHibernateRecorder[event.subject, time, nowTimestamp]
            .take(PluginConfig.historyMessageLimit) // 只取最近的部分消息，避免上下文过长
            .sortedBy { it.time } // 按时间排序
            .toMutableList()

        // 有一定概率最后一条消息没加入，这里检查然后补充一下
        val msgIds = event.message.ids.joinToString(",")
        if (!history.any { it.ids == msgIds }) {
            history.add(MessageRecord.fromSuccess(event.message.source, event.message))
        }

        // 构造历史消息
        val historyText = StringBuilder()
        var lastId = 0L
        var lastTime = 0L
        // 本轮回复索引，逐条登记消息编号供 [n] 引用
        val replyIndex = replyIndexMap.getOrPut(event.subject.id) { ReplyIndex() }
        if (event is GroupMessageEvent) {
            if (PluginConfig.enableFavorabilitySystem) {
                val knownUsers = history.asSequence()
                    .map { it.fromId }
                    .filter { it != event.bot.id }
                    .distinct()
                    .mapNotNull { PluginData.userFavorability[it] }
                    .filter { it.name.isNotEmpty() || it.tags.isNotEmpty() || it.impression.isNotEmpty() }
                    .sortedBy { it.userId }
                    .toList()
                if (knownUsers.isNotEmpty()) {
                    historyText.appendLine("【你认识的群友】")
                    for (info in knownUsers) {
                        val displayName = if (info.name.isNotEmpty()) info.name
                                          else getNameCard(event.subject, info.userId)
                        historyText.append("- ").append(displayName)
                            .append("(${info.userId})")
                            .append(" 好感度${if (info.value >= 0) "+" else ""}${info.value}")
                        if (info.tags.isNotEmpty()) historyText.append(" [${info.tags.joinToString(", ")}]")
                        if (info.impression.isNotEmpty()) historyText.append(" ${info.impression}")
                        historyText.appendLine()
                    }
                    historyText.appendLine()
                }
            }

            historyText.appendLine("## 近期群消息（更早已隐藏，行首[n]为消息编号，可用于引用回复）")
            for (record in history) {
                // 同一人发言不要反复出现这人的名字，减少上下文
                val showSender = lastId != record.fromId
                val showTime = showSender || record.time.toLong() - lastTime > CONTINUATION_TIME_GAP_SECONDS
                appendGroupMessageRecord(historyText, record, event, replyIndex, showSender, showTime)
                lastId = record.fromId
                lastTime = record.time.toLong()
            }
        } else {
            if (PluginConfig.enableFavorabilitySystem) {
                val favorabilityInfo = PluginData.userFavorability[event.sender.id]
                if (favorabilityInfo != null && (favorabilityInfo.name.isNotEmpty() || favorabilityInfo.tags.isNotEmpty() || favorabilityInfo.impression.isNotEmpty())) {
                    val displayName = if (favorabilityInfo.name.isNotEmpty()) favorabilityInfo.name else event.senderName
                    historyText.appendLine("【你认识的对方】")
                    historyText.append("- ").append(displayName)
                        .append("(${event.sender.id})")
                        .append(" 好感度${if (favorabilityInfo.value >= 0) "+" else ""}${favorabilityInfo.value}")
                    if (favorabilityInfo.tags.isNotEmpty()) historyText.append(" [${favorabilityInfo.tags.joinToString(", ")}]")
                    if (favorabilityInfo.impression.isNotEmpty()) historyText.append(" ${favorabilityInfo.impression}")
                    historyText.appendLine().appendLine()
                }
            }

            historyText.appendLine("## 近期对话（更早已隐藏，行首[n]为消息编号，可用于引用回复）")
            for (record in history) {
                // 同一人发言不要反复出现这人的名字，减少上下文
                val showSender = lastId != record.fromId
                val showTime = showSender || record.time.toLong() - lastTime > CONTINUATION_TIME_GAP_SECONDS
                appendMessageRecord(historyText, record, event, replyIndex, showSender, showTime)
                lastId = record.fromId
                lastTime = record.time.toLong()
            }
        }

        return historyText.toString()
    }

    /**
     * 添加群消息记录到历史上下文中
     * @param historyText 历史消息构造器
     * @param record 群消息记录
     * @param event 群消息事件
     */
    fun appendGroupMessageRecord(
        historyText: StringBuilder,
        record: MessageRecord,
        event: GroupMessageEvent,
        replyIndex: ReplyIndex,
        showSender: Boolean,
        showTime: Boolean,
    ) {
        val index = replyIndex.add(record)
        val recordMessage = record.toMessageChain()

        historyText.append('[').append(index).append("] ")
        if (showSender) {
            // 新发言者：[n] 名称 时间
            if (event.bot.id == record.fromId) {
                historyText.append("**你** ").append(getNameCard(event.subject.botAsMember))
            } else {
                historyText.append(getNameCard(event.subject, record.fromId))
            }
            historyText.append(' ')
                .append(shortTimeFormatter.format(Instant.ofEpochSecond(record.time.toLong())))
                .append(' ')
        } else {
            // 同一发言者续行；间隔过久则补回时间，避免被误判为很久以前发生
            historyText.append(" └ ")
            if (showTime) {
                historyText.append(shortTimeFormatter.format(Instant.ofEpochSecond(record.time.toLong())))
                    .append(' ')
            }
        }

        // 引用：用编号指针替代内联原文，避免被误认为是本人发言
        recordMessage[QuoteReply.Key]?.let {
            appendQuoteMarker(historyText, it, event.subject, replyIndex)
        }

        historyText.appendLine(formatRecordContent(recordMessage, event.subject))
    }

    /**
     * 序列化「引用回复」标记：被引用消息在窗口内时用 ↩[编号]，否则内联简短原文并标注原作者。
     */
    private fun appendQuoteMarker(
        sb: StringBuilder,
        quote: QuoteReply,
        contact: Contact,
        replyIndex: ReplyIndex
    ) {
        val srcIds = quote.source.ids.joinToString(",")
        val idx = replyIndex.indexOfIds(srcIds)
        if (idx != null) {
            sb.append("↩[").append(idx).append("] ")
        } else {
            val author = if (contact is Group) {
                contact[quote.source.fromId]?.nameCardOrNick ?: "未知(${quote.source.fromId})"
            } else {
                quote.source.fromId.toString()
            }
            val snippet = quote.source.originalMessage
                .joinToString("", transform = ::singleMessageToText)
                .replace("\n", " ")
                .let { if (it.length > 20) it.take(20) + "…" else it }
            sb.append("↩(").append(author).append(":\"").append(snippet).append("\") ")
        }
    }

    /**
     * 序列化消息正文（剔除引用/源元数据，@显示为名称，转发折叠）。
     */
    private fun formatRecordContent(chain: MessageChain, contact: Contact): String =
        chain.asSequence()
            .filterNot { it is QuoteReply || it is MessageSource }
            .joinToString("") {
                when (it) {
                    is At -> if (contact is Group) it.getDisplay(contact) else it.content
                    else -> singleMessageToText(it)
                }
            }

    private fun getNameCard(group: Group, qq: Long): String {
        val member = group[qq]
        return if (member == null) {
            "未知群员($qq)"
        } else {
            getNameCard(member)
        }
    }

    /**
     * 添加消息记录到历史上下文中
     * @param historyText 历史消息构造器
     * @param record 消息记录
     * @param event 消息事件
     */
    fun appendMessageRecord(
        historyText: StringBuilder,
        record: MessageRecord,
        event: MessageEvent,
        replyIndex: ReplyIndex,
        showSender: Boolean,
        showTime: Boolean,
    ) {
        val index = replyIndex.add(record)
        val recordMessage = record.toMessageChain()

        historyText.append('[').append(index).append("] ")
        if (showSender) {
            if (event.bot.id == record.fromId) {
                historyText.append("**你** ").append(event.bot.nameCardOrNick)
            } else {
                historyText.append(event.senderName)
            }
            historyText.append(' ')
                .append(shortTimeFormatter.format(Instant.ofEpochSecond(record.time.toLong())))
                .append(' ')
        } else {
            // 同一发言者续行；间隔过久则补回时间，避免被误判为很久以前发生
            historyText.append(" └ ")
            if (showTime) {
                historyText.append(shortTimeFormatter.format(Instant.ofEpochSecond(record.time.toLong())))
                    .append(' ')
            }
        }

        recordMessage[QuoteReply.Key]?.let {
            appendQuoteMarker(historyText, it, event.subject, replyIndex)
        }

        historyText.appendLine(formatRecordContent(recordMessage, event.subject))
    }

    private fun singleMessageToText(it: SingleMessage): String {
        return when (it) {
            // 完整展开合并转发内容，便于 LLM 阅读分析转发的对话（依赖大上下文+缓存，不做截断）
            is ForwardMessage -> formatForward(it, 1)

            // 图片格式化
            is Image -> {
                try {
                    val imageUrl = runBlocking {
                        it.queryUrl()
                    }
                    "![${if (it.isEmoji) "表情包" else "图片"}]($imageUrl)"
                } catch (e: Throwable) {
                    logger.warning("图片地址获取失败", e)
                    it.content
                }
            }

            else -> it.content
        }
    }

    /**
     * 递归展开合并转发消息，用 Markdown 引用块表示：每加深一层嵌套多一个 `>`（>、>>、>>>…）。
     * @param depth 当前嵌套层级，从 1 开始
     */
    private fun formatForward(forward: ForwardMessage, depth: Int): String = buildString {
        val quote = ">".repeat(depth) + " "
        append("[转发消息·").append(forward.nodeList.size).append("条")
        if (forward.title.isNotEmpty()) append(':').append(forward.title)
        append(']')
        for (node in forward.nodeList) {
            append('\n').append(quote)
                .append(node.senderName).append(' ')
                .append(shortTimeFormatter.format(Instant.ofEpochSecond(node.time.toLong())))
                .append(": ")
            node.messageChain.forEach { sub ->
                if (sub is ForwardMessage) {
                    // 嵌套转发：层级加深，自带更深的 `>` 前缀，无需再次缩进
                    append(formatForward(sub, depth + 1))
                } else {
                    // 其它内容：多行正文对齐到当前引用层级
                    append(singleMessageToText(sub).replace("\n", "\n$quote"))
                }
            }
        }
    }

    // endregion - 历史消息相关 -

    private val thinkRegex = Regex("<think>[\\s\\S]*?</think>")

    /**
     * 截断过长的工具输出，并添加省略标记
     */
    private fun truncateToolOutput(content: String, maxLength: Int = PluginConfig.maxToolOutputLength): String {
        if (content.length <= maxLength) return content

        val truncated = content.take(maxLength)
        val marker = "\n\n[系统提示：因内容过长，部分内容已被省略]"
        return truncated + marker
    }

    private suspend fun startChat(event: MessageEvent) {
        if (!requestMap.add(event.subject.id)) {
            logger.warning("The current Contact is busy!")
            return
        }

        try {
            // 尝试从缓存加载上下文
            val subjectId = event.subject.id
            val cache = contextCache[subjectId]
            val reuseCache = PluginConfig.enableContextCache
                && cache != null
                && !cache.isExpired(PluginConfig.contextCacheTimeoutMinutes * 60)
            // 回复索引与对话上下文同寿命：复用缓存时沿用旧索引，保证 LLM 看到的 [n] 编号连续不串号；
            // 否则新建（供 sendSingleMessage 的 replyTo 按编号引用历史消息）
            val replyIndex = if (reuseCache) cache!!.replyIndex else ReplyIndex()
            replyIndexMap[subjectId] = replyIndex
            val history = if (reuseCache) {
                // 缓存有效，复用历史
                logger.info("使用缓存的对话上下文，包含 ${cache!!.history.size} 条互动消息")
                cache.history
            } else {
                // 缓存无效或不存在，创建新上下文
                mutableListOf()
            }

            // 如果历史为空，添加系统提示词和聊天记录
            if (history.isEmpty() || cache == null) {
                val prompt = getSystemPrompt(event)
                if (PluginConfig.logPrompt) {
                    logger.info("Prompt: $prompt")
                }
                history.add(ChatMessage(ChatRole.System, prompt))

                val historyText = getHistory(event)
                logger.info("注入聊天记录：\n$historyText")
                history.add(ChatMessage.User(historyText))
            } else {
                val newMessages = getAfterHistory(cache.lastActivityAt, event)
                logger.info("补充聊天记录：\n$newMessages")
                history.add(
                    ChatMessage.User(
                        "## 以下是上次对话结束至今的新消息\n\n$newMessages"
                    )
                )
            }

            // 聊天接入点容灾：按健康度排序，主接入点故障冷却时备用接入点会自动排到前面
            val endpoints = LargeLanguageModels.orderedChatEndpoints()
            if (endpoints.isEmpty()) throw NullPointerException("OpenAI Token 未设置，无法开始")
            var endpointIndex = 0

            var done: Boolean
            // 至少循环3次
            var retry = max(PluginConfig.retryMax, 3)
            do {
                // 当前使用的接入点：失败重试时会前移到下一个备用接入点
                val endpoint = endpoints[min(endpointIndex, endpoints.lastIndex)]
                // 标记本轮 LLM 流式调用是否成功完成，用于精确区分「LLM失败」与「后续工具失败」
                var streamingOk = false
                try {
                    val startedAt = OffsetDateTime.now().toEpochSecond().toInt()
                    var lastCacheUsage: ModelService.CacheUsage? = null
                    val responseFlow = chatCompletions(history, endpoint) { lastCacheUsage = it }
                    var responseMessageBuilder: StringBuilder? = null
                    var reasoningContentBuilder: StringBuilder? = null
                    val responseToolCalls = mutableListOf<ToolCall.Function>()
                    val toolCallTasks = mutableListOf<Deferred<ChatMessage>>()
                    var lastTokenUsage: Usage? = null
                    // 处理聊天流式响应
                    responseFlow.collect { chunk ->
                        val delta = chunk.choices[0].delta ?: return@collect

                        // 处理推理内容更新
                        if (delta.reasoningContent != null) {
                            if (reasoningContentBuilder == null) {
                                reasoningContentBuilder = StringBuilder(delta.reasoningContent)
                            } else {
                                reasoningContentBuilder.append(delta.reasoningContent)
                            }
                        }

                        // 处理内容更新
                        if (delta.content != null) {
                            if (responseMessageBuilder == null) {
                                responseMessageBuilder = StringBuilder(delta.content)
                            } else {
                                responseMessageBuilder.append(delta.content)
                            }
                        }

                        // 处理工具调用更新
                        val toolCalls = delta.toolCalls
                        if (toolCalls != null) {
                            for (toolCallChunk in toolCalls) {
                                val index = toolCallChunk.index
                                val toolId = toolCallChunk.id
                                val function = toolCallChunk.function
                                // 新的请求
                                if (index >= responseToolCalls.size) {
                                    // 处理已完成的工具调用
                                    responseToolCalls.lastOrNull()?.let { toolCall ->
                                        toolCallTasks.add(async {
                                            val functionResponse = toolCall.execute(event)
                                            ChatMessage(
                                                role = ChatRole.Tool,
                                                toolCallId = toolCall.id,
                                                name = toolCall.function.name,
                                                content = functionResponse
                                            )
                                        })
                                    }

                                    // 加入新的工具调用
                                    if (toolId != null && function != null) {
                                        responseToolCalls.add(ToolCall.Function(toolId, function))
                                    }
                                } else if (function != null) {
                                    // 拼接函数名字
                                    if (function.nameOrNull != null) {
                                        val currentTool = responseToolCalls[index]
                                        responseToolCalls[index] = currentTool.copy(
                                            function = currentTool.function.copy(
                                                nameOrNull = currentTool.function.nameOrNull.orEmpty() + function.name
                                            )
                                        )
                                    }
                                    // 拼接函数参数
                                    if (function.argumentsOrNull != null) {
                                        val currentTool = responseToolCalls[index]
                                        responseToolCalls[index] = currentTool.copy(
                                            function = currentTool.function.copy(
                                                argumentsOrNull = currentTool.function.argumentsOrNull.orEmpty() + function.arguments
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // 捕获token使用量
                        chunk.usage?.let { lastTokenUsage = it }
                    }
                    // LLM 流式调用成功完成，上报接入点健康（清除冷却）
                    streamingOk = true
                    LargeLanguageModels.reportSuccess(endpoint)

                    // 移除思考内容
                    val responseContent = responseMessageBuilder?.replace(thinkRegex, "")?.trim()
                    logger.info("LLM Response: $responseContent")
                    // 记录AI回答
                    // reasoning_content仅在工具调用时需要回传（DeepSeek规范），否则丢弃
                    // toolCalls空列表转null，避免序列化为"tool_calls":[]导致DeepSeek V4报400
                    // explicitNulls=false确保null字段不会序列化到JSON中，兼容所有API
                    history.add(
                        ChatMessage(
                            role = ChatRole.Assistant,
                            content = responseContent,
                            toolCalls = responseToolCalls.ifEmpty { null },
                            reasoningContent = if (responseToolCalls.isNotEmpty()) reasoningContentBuilder?.toString() else null
                        )
                    )

                    // 记录token使用量（按日聚合，独立JSON文件）
                    lastTokenUsage?.let { usage ->
                        val now = OffsetDateTime.now().toEpochSecond()
                        val group = if (event is GroupMessageEvent) event.group else null
                        TokenUsageStore.record(
                            timestamp = now,
                            userId = event.sender.id,
                            userNickname = event.senderName,
                            groupId = group?.id,
                            groupName = group?.name,
                            promptTokens = usage.promptTokens ?: 0,
                            completionTokens = usage.completionTokens ?: 0,
                            totalTokens = usage.totalTokens ?: 0,
                            cachedTokens = lastCacheUsage?.hitTokens ?: 0
                        )
                    }

                    // 处理最后一个工具调用
                    if (responseToolCalls.size > toolCallTasks.size) {
                        val toolCallMessage = responseToolCalls.last().let { toolCall ->
                            val functionResponse = toolCall.execute(event)
                            ChatMessage(
                                role = ChatRole.Tool,
                                toolCallId = toolCall.id,
                                name = toolCall.function.name,
                                content = functionResponse
                            )
                        }

                        if (toolCallTasks.isNotEmpty()) {
                            // 等待之前的所有工具完成
                            history.addAll(toolCallTasks.awaitAll())
                        }
                        // 将最后一个也加入对话历史中
                        history.add(toolCallMessage)
                        // 如果调用中包含结束对话工具则表示完成，反之则继续循环
                        done = responseToolCalls.any { it.function.name == "endConversation" }
                    } else {
                        done = true
                    }

                    if (!done) {
                        history.add(
                            ChatMessage.User(
                            buildString {
                                appendLine("## 系统提示")
                                append("本次运行最多还剩").append(retry - 1).appendLine("轮。")
                                appendLine("如果要多次发言，可以一次性调用多次发言工具。")
                                appendLine("如果没有什么要做的，可以提前结束。")
                                appendLine("当前时间：" + dateTimeFormatter.format(OffsetDateTime.now()))

                                val newMessages = getAfterHistory(startedAt, event)
                                if (newMessages.isNotEmpty()) {
                                    append("## 以下是上次运行至今的新消息\n\n$newMessages")
                                }
                            }
                        ))
                    } else {
                        // 保存对话上下文到缓存
                        if (PluginConfig.enableContextCache) {
                            contextCache[subjectId] = ConversationCache(
                                history = history,
                                lastActivityAt = startedAt,
                                replyIndex = replyIndex
                            )
                            logger.debug("已保存对话上下文到缓存")
                        }
                    }
                } catch (e: Exception) {
                    // 仅当 LLM 流式调用本身失败时才上报接入点故障并切换；
                    // 若流式已成功、异常来自后续工具执行，则保持当前接入点不变
                    if (!streamingOk) {
                        LargeLanguageModels.reportFailure(endpoint)
                        if (endpointIndex < endpoints.lastIndex) {
                            endpointIndex++
                            logger.warning("接入点[${endpoint.label}]调用失败，切换备用接入点[${endpoints[endpointIndex].label}]重试", e)
                        } else {
                            logger.warning("接入点[${endpoint.label}]调用失败，无更多备用接入点，重试中", e)
                        }
                    } else {
                        logger.warning("调用llm后处理时发生异常，重试中", e)
                    }
                    if (retry <= 1) {
                        throw e
                    } else {
                        done = false
                        // event.subject.sendMessage("出错了...正在重试...")
                    }
                }
            } while (!done && 0 < --retry)
        } catch (ex: Throwable) {
            logger.warning(ex)
            event.subject.sendMessage("很抱歉，发生异常，请稍后重试")
        } finally {
            // 清理本轮回复索引
            replyIndexMap.remove(event.subject.id)
            // 一段时间后才允许再次提问，防止高频对话
            launch {
                delay(500.milliseconds)
                requestMap.remove(event.subject.id)
            }
        }
    }

    private val regexAtQq = Regex("""@(\d{5,12})""")

    private val regexImage = Regex("""!\[(.*?)]\(([^\s"']+).*?\)""")

    private data class MessageChunk(val range: IntRange, val content: Message)

    /**
     * 将聊天内容转为聊天消息
     *
     * @param contact 联系对象
     * @param content 文本内容
     * @return 构造的消息
     */
    fun toMessage(contact: Contact, content: String): Message {
        return if (content.isEmpty()) {
            PlainText("...")
        } else if (content.length < 3) {
            PlainText(content)
        } else {
            val t = mutableListOf<MessageChunk>()
            // @某人
            regexAtQq.findAll(content).forEach {
                val qq = it.groups[1]?.value?.toLongOrNull()
                if (qq != null && contact is Group) {
                    contact[qq]?.let { member -> t.add(MessageChunk(it.range, At(member))) }
                }
            }

            // 图片
            regexImage.findAll(content).forEach {
                // val placeholder = it.groupValues[1]
                val url = it.groupValues[2]
                t.add(
                    MessageChunk(
                        it.range,
                        Image(url)
                    )
                )
            }

            // 构造消息链
            buildMessageChain {
                var index = 0
                for ((range, msg) in t.sortedBy { it.range.first }) {
                    if (index < range.first) {
                        append(content, index, range.first)
                    }
                    append(msg)
                    index = range.last + 1
                }
                // 拼接后续消息
                if (index < content.length) {
                    append(content, index, content.length)
                }
            }
        }
    }

    /**
     * 工具列表
     */
    private val myTools = listOf(
        // 发送单条消息
        SendSingleMessageAgent(),

        // 发送组合消息
        SendCompositeMessage(),

        // 发送语音消息
        SendVoiceMessage(),

        // 发送LaTeX表达式
        SendLaTeXExpression(),

        // 结束循环
        StopLoopAgent(),

        // 记忆代理
        MemoryAppend(),

        // 记忆修改
        MemoryReplace(),

        // 技能：加载
        LoadSkill(),

        // 技能：沉淀/迭代
        SaveSkill(),

        // 技能：删除
        DeleteSkill(),

        // 搜索聊天历史
        SearchChatHistory(),

        // 网页搜索
        WebSearch(),

        // 访问网页
        VisitWeb(),

        // 运行代码
        RunCode(),

        // 推理代理
        ReasoningAgent(),

        // 视觉代理
        VisualAgent(),

        // 图像生成与编辑
        ImageAgent(),

        // 天气服务
        WeatherService(),

        // 好感度调整
        AdjustUserFavorabilityAgent(),

        // 请求主人帮助
        RequestOwner(),

        // Epic 免费游戏
        // EpicFreeGame(),

        // 群管代理
        GroupManageAgent(),
    )

//    private suspend fun chatCompletion(
//        chatMessages: List<ChatMessage>,
//        hasTools: Boolean = true
//    ): ChatMessage {
//        val llm = LargeLanguageModels.chat ?: throw NullPointerException("OpenAI Token 未设置，无法开始")
//        val availableTools = if (hasTools) {
//            myTools.filter { it.isEnabled }.map { it.tool }
//        } else null
//        val request = ChatCompletionRequest(
//            model = ModelId(PluginConfig.chatModel),
//            temperature = PluginConfig.chatTemperature,
//            messages = chatMessages,
//            tools = availableTools,
//        )
//        logger.info("API Requesting... Model=${PluginConfig.chatModel}")
//        val response = llm.chatCompletion(request)
//        val message = response.choices.first().message
//        logger.info("Response: $message ${response.usage}")
//        return message
//    }

    private fun chatCompletions(
        chatMessages: List<ChatMessage>,
        endpoint: LargeLanguageModels.ChatEndpoint,
        hasTools: Boolean = true,
        onCacheUsage: ((ModelService.CacheUsage) -> Unit)? = null
    ): Flow<ChatCompletionChunk> {
        val availableTools = if (hasTools) {
            myTools.filter { it.isEnabled }.map { it.tool }
        } else null
        val request = ChatCompletionRequest(
            model = ModelId(endpoint.model),
            temperature = endpoint.temperature,
            messages = chatMessages,
            tools = availableTools,
        )
        logger.info("API Requesting... Model=${endpoint.model} [${endpoint.label}]")
        return endpoint.service.chatCompletions(request, onCacheUsage)
    }

    private fun getNameCard(member: Member): String {
        val nameCard = StringBuilder("【")
        // 群活跃等级：active 依赖 OneBot 拉取群荣誉数据，繁忙/失败时会抛 "Error code: 2"，
        // 必须兜底，否则整次回复都会因取名片失败而中断。
        try {
            nameCard.append("lv").append(member.active.temperature).append(' ')
        } catch (e: Throwable) {
            logger.warning("获取群活跃等级失败", e)
        }
        // 真实群身份：始终按实际权限显示，不会被专属头衔覆盖
        nameCard.append(
            when (member.permission) {
                OWNER -> "群主"
                ADMINISTRATOR -> "管理员"
                MEMBER -> "群员"
            }
        )
        // 头衔：有专属头衔则显示专属头衔（群主可任意赋予，可能与真实身份不符，故标注"头衔"以区分），
        // 否则回退到聊天窗口可见的活跃等级称号
        try {
            if (member.specialTitle.isNotEmpty()) {
                nameCard.append(" 头衔\"").append(member.specialTitle).append('"')
            } else if (member.temperatureTitle.isNotEmpty()) {
                nameCard.append(' ').append(member.temperatureTitle)
            }
        } catch (e: Throwable) {
            logger.warning("获取群头衔失败", e)
        }
        // 群名片
        nameCard.append("】\t\"").append(member.nameCardOrNick).append("\"\t(qq=").append(member.id).append(")")
        return nameCard.toString()
    }


    private suspend fun ToolCall.Function.execute(event: MessageEvent): String {
        val agent = myTools.find { it.tool.function.name == function.name }
            ?: return "Function ${function.name} not found"
        // 提示正在执行函数
        val receipt = if (PluginConfig.showToolCallingMessage && agent.loadingMessage.isNotEmpty()) {
            event.subject.sendMessage(agent.loadingMessage)
        } else null
        // 执行函数
        val result = try {
            // 提取参数
            val args = function.argumentsAsJsonOrNull()
            logger.info("Calling ${function.name}(${args})")
            agent.execute(args, event)
        } catch (e: Throwable) {
            logger.error("Failed to call ${function.name}", e)
            "工具调用失败，请尝试自行回答用户，或如实告知。\n异常信息：${e.message}"
        }
        logger.info("Result=\"$result\"")

        // 截断过长的工具输出
        val truncatedResult = truncateToolOutput(result)
        if (truncatedResult.length != result.length) {
            logger.warning("工具 ${function.name} 返回内容过长，已从 ${result.length} 字符截断至 ${truncatedResult.length} 字符")
        }

        // 过会撤回加载消息
        if (receipt != null) {
            launch {
                delay(3.seconds)
                try {
                    receipt.recall()
                } catch (e: Throwable) {
                    logger.error(
                        "消息撤回失败，调试信息：" +
                                "source.internalIds=${receipt.source.internalIds.joinToString()} " +
                                "source.ids= ${receipt.source.ids.joinToString()}", e
                    )
                }
            }
        }
        return truncatedResult
    }

    /**
     * 好感度时间偏移处理函数
     * 使好感度逐渐向0回归，偏移速度与当前好感度绝对值相关
     */
    private fun shiftFavorabilityOverTime() {
        logger.info("开始执行好感度时间偏移处理")

        val iterator = PluginData.userFavorability.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val userId = entry.key
            val favorabilityInfo = entry.value
            val currentFavorability = favorabilityInfo.value

            // 计算偏移量
            // 偏移公式：偏移量 = sign(好感度) * (1 - (|好感度| / 100)^2) * 基础偏移速度
            val sign = sign(currentFavorability.toFloat()).toInt()
            val absFavorability = kotlin.math.abs(currentFavorability)
            val shiftAmount = sign * (1 - (absFavorability / 100.0).pow(2)) * PluginConfig.favorabilityBaseShiftSpeed

            // 更新好感度
            val newFavorability = (currentFavorability - shiftAmount).toInt().coerceIn(-100, 100)

            // 如果新的好感度为0，则移除该条目以节省空间
            if (newFavorability == 0) {
                iterator.remove()
                logger.info("用户 $userId 的好感度已回归0，移除记录")
            } else {
                // 创建新的好感度信息，保持原因和印象不变
                val newInfo = favorabilityInfo.copy(value = newFavorability)
                PluginData.userFavorability[userId] = newInfo
                logger.info("用户 $userId 的好感度 ($currentFavorability -> $newFavorability)")
            }
        }

        logger.info("好感度时间偏移处理完成")
    }
}