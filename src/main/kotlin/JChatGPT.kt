package top.jie65535.mirai

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
import io.ktor.util.collections.*
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.register
import net.mamoe.mirai.console.command.CommandSender.Companion.toCommandSender
import net.mamoe.mirai.console.permission.PermissionId
import net.mamoe.mirai.console.permission.PermissionService
import net.mamoe.mirai.console.permission.PermissionService.Companion.hasPermission
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.contact.isOperator
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.FriendMessageEvent
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.message.data.MessageSource.Key.quote
import net.mamoe.mirai.message.sourceIds
import net.mamoe.mirai.utils.info

object JChatGPT : KotlinPlugin(
    JvmPluginDescription(
        id = "top.jie65535.mirai.JChatGPT",
        name = "J ChatGPT",
        version = "1.0.0",
    ) {
        author("jie65535")
    }
) {
    private var openAi: OpenAI? = null

    val chatPermission = PermissionId("JChatGPT", "Chat")

    override fun onEnable() {
        // 注册聊天权限
        PermissionService.INSTANCE.register(chatPermission, "JChatGPT Chat Permission")
        PluginConfig.reload()

        // 设置Token
        if (PluginConfig.openAiToken.isNotEmpty()) {
            updateOpenAiToken(PluginConfig.openAiToken)
        }

        // 注册插件命令
        PluginCommands.register()

        GlobalEventChannel.parentScope(this)
            .subscribeAlways<MessageEvent> { event -> onMessage(event) }

        logger.info { "Plugin loaded" }
    }

    fun updateOpenAiToken(token: String) {
        openAi = OpenAI(token,
            host = OpenAIHost(baseUrl = PluginConfig.openAiApi)
        )
    }

//    private val userContext = ConcurrentMap<Long, MutableList<ChatMessage>>()
    private const val REPLAY_QUEUE_MAX = 30
    private val replyMap = ConcurrentMap<Int, MutableList<ChatMessage>>()
    private val replyQueue = mutableListOf<Int>()
    private val requestMap = ConcurrentSet<Long>()

    private suspend fun MessageEvent.onMessage(event: MessageEvent) {
        // 检查Token是否设置
        if (openAi == null) return
        // 发送者是否有权限
        if (!toCommandSender().hasPermission(chatPermission)) {
            if (this is GroupMessageEvent) {
                if (!sender.isOperator() || !PluginConfig.groupOpHasChatPermission) {
                    return
                }
            }
            if (this is FriendMessageEvent) {
                if (!PluginConfig.friendHasChatPermission) {
                    return
                }
                // TODO 检查好友上下文
            }
        }

        // 是否@bot
        val isAtBot = message.contains(At(bot))
        // 是否包含引用消息
        val quote = message[QuoteReply]
        // 如果没有@bot或者引用消息则直接结束
        if (!isAtBot && quote == null)
            return

        // 如果有引用消息，则尝试从回复记录中找到对应消息
        var context: List<ChatMessage>? = if (quote != null) {
            replyMap[quote.source.ids[0]]
        } else null

        // 如果没有At机器人同时上下文是空的，直接忽略
        if (!isAtBot && context == null) return


        if (context == null) {
            // 如果没有上下文但是引用了消息并且at了机器人，则用引用的消息内容作为上下文
            if (quote != null) {
                val msg = quote.source.originalMessage.plainText()
                if (msg.isNotEmpty()) {
                    context = listOf(ChatMessage(ChatRole.User, msg))
                }
            }
        }

        startChat(context)
    }

    private suspend fun MessageEvent.startChat(context: List<ChatMessage>? = null) {
        val history = mutableListOf<ChatMessage>()
        if (!context.isNullOrEmpty()) {
            history.addAll(context)
        } else if (PluginConfig.prompt.isNotEmpty()) {
            history.add(ChatMessage(ChatRole.System, PluginConfig.prompt))
        }
        val msg = message.plainText()
        if (msg.isNotEmpty()) {
            history.add(ChatMessage(ChatRole.User, msg))
        }

        try {
            if (!requestMap.add(sender.id)) {
                subject.sendMessage(message.quote() + "再等等...")
                return
            }
            val reply = chatCompletion(history)
            history.add(reply)
            val replyMsg = subject.sendMessage(message.quote() + (reply.content ?: "..."))
            val msgId = replyMsg.sourceIds[0]
            replyMap[msgId] = history
            replyQueue.add(msgId)
            if (replyQueue.size > REPLAY_QUEUE_MAX) {
                replyMap.remove(replyQueue.removeAt(0))
            }
        } catch (ex: Throwable) {
            logger.warning(ex)
            subject.sendMessage(message.quote() + "发生异常，请重试")
        } finally {
            requestMap.remove(sender.id)
        }
    }

    private suspend fun chatCompletion(messages: List<ChatMessage>): ChatMessage {
        val openAi = this.openAi ?: throw NullPointerException("OpenAI Token 未设置，无法开始")
        val request = ChatCompletionRequest(ModelId(PluginConfig.chatModel), messages)
        logger.info("OpenAI API Requesting...  Model=${PluginConfig.chatModel}")
        val response = openAi.chatCompletion(request)
        logger.info("OpenAI API Usage: ${response.usage}")
        return response.choices.first().message
    }

    private fun MessageChain.plainText() = this.filterIsInstance<PlainText>().joinToString().trim()
}