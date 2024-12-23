package top.jie65535.mirai

import com.aallam.openai.api.chat.*
import com.aallam.openai.api.core.Role
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
import io.ktor.util.collections.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.register
import net.mamoe.mirai.console.command.CommandSender.Companion.toCommandSender
import net.mamoe.mirai.console.permission.PermissionId
import net.mamoe.mirai.console.permission.PermissionService
import net.mamoe.mirai.console.permission.PermissionService.Companion.hasPermission
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.contact.isOperator
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.FriendMessageEvent
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.message.data.MessageSource.Key.quote
import net.mamoe.mirai.message.sourceIds
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import net.mamoe.mirai.utils.info
import top.jie65535.mirai.tools.*
import java.time.OffsetDateTime
import java.time.format.TextStyle
import java.util.*
import java.util.regex.Pattern
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object JChatGPT : KotlinPlugin(
    JvmPluginDescription(
        id = "top.jie65535.mirai.JChatGPT",
        name = "J ChatGPT",
        version = "1.3.0",
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
        val timeout = PluginConfig.timeout.milliseconds
        openAi = OpenAI(
            token,
            host = OpenAIHost(baseUrl = PluginConfig.openAiApi),
            timeout = Timeout(request = timeout, connect = timeout, socket = timeout)
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
                if (PluginConfig.groupOpHasChatPermission && sender.isOperator()) {
                    // 允许管理员使用
                } else if (sender.active.temperature >= PluginConfig.temperaturePermission) {
                    // 允许活跃度达标成员使用
                } else {
                    // 其它情况阻止使用
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

    private fun MessageEvent.getSystemPrompt(): String {
        val now = OffsetDateTime.now()
        val prompt = StringBuilder(PluginConfig.prompt)
        fun replace(target: String, replacement: ()->String) {
            val i = prompt.indexOf(target)
            if (i != -1) {
                prompt.replace(i, i + target.length, replacement())
            }
        }
        replace("{time}") {
            "$now ${now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)}"
        }
        replace("{subject}") {
            if (this is GroupMessageEvent) {
                "《${subject.name}》群聊中"
            } else {
                "私聊中"
            }
        }
        replace("{sender}") {
            senderName
        }
        return prompt.toString()
    }

    private suspend fun MessageEvent.startChat(context: List<ChatMessage>? = null) {
        val history = mutableListOf<ChatMessage>()
        if (!context.isNullOrEmpty()) {
            history.addAll(context)
        } else if (PluginConfig.prompt.isNotEmpty()) {
            history.add(ChatMessage(ChatRole.System, getSystemPrompt()))
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

            var done: Boolean
            var retry = 2
            var hasTools = true
            do {
                val reply = chatCompletion(history, hasTools)
                history.add(reply)
                done = true

                for (toolCall in reply.toolCalls.orEmpty()) {
                    require(toolCall is ToolCall.Function) { "Tool call is not a function" }
                    val functionResponse = toolCall.execute(this)
                    history.add(
                        ChatMessage(
                            role = ChatRole.Tool,
                            toolCallId = toolCall.id,
                            name = toolCall.function.name,
                            content = functionResponse
                        )
                    )
                    done = false
                    hasTools = false
                }
            } while (!done && 0 < --retry)

            val content = history.last().content ?: "..."
            val replyMsg = subject.sendMessage(
                if (content.length < 128) {
                    message.quote() + toMessage(subject, content)
                } else {
                    // 消息内容太长则转为转发消息避免刷屏
                    buildForwardMessage {
                        for (item in history) {
                            if (item.content.isNullOrEmpty())
                                continue
                            val temp = toMessage(subject, item.content!!)
                            when (item.role) {
                                Role.User -> sender says temp
                                Role.Assistant -> bot says temp
                            }
                        }
                    }
                }
            )
            if (replyMsg.sourceIds.isNotEmpty()) {
                val msgId = replyMsg.sourceIds[0]
                replyMap[msgId] = history
                replyQueue.add(msgId)
            }
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

    private val laTeXPattern = Pattern.compile(
        "\\\\\\((.+?)\\\\\\)|" + // 匹配行内公式 \(...\)
                "\\\\\\[(.+?)\\\\\\]|" + // 匹配独立公式 \[...\]
                "\\$\\$([^$]+?)\\$\\$|" + // 匹配独立公式 $$...$$
                "\\$(.+?)\\$|" + // 匹配行内公式 $...$
                "```latex\\s*([^`]+?)\\s*```" // 匹配 ```latex ... ```
        , Pattern.DOTALL
    )

    /**
     * 将聊天内容转为聊天消息，如果聊天中包含LaTeX表达式，将会转为图片拼接到消息中。
     *
     * @param contact 联系对象
     * @param content 文本内容
     * @return 构造的消息
     */
    private suspend fun toMessage(contact: Contact, content: String): Message {
        return if (content.isEmpty()) {
            PlainText("...")
        } else if (content.length < 3) {
            PlainText(content)
        } else buildMessageChain {
            // 匹配LaTeX表达式
            val matcher = laTeXPattern.matcher(content)
            var index = 0
            while (matcher.find()) {
                for (i in 1..matcher.groupCount()) {
                    if (matcher.group(i) == null) {
                        continue
                    }
                    try {
                        // 将所有匹配的LaTeX公式转为图片拼接到消息中
                        val formula = matcher.group(i)
                        val imageByteArray = LaTeXConverter.convertToImage(formula, "png")
                        val resource = imageByteArray.toExternalResource("png")
                        val image = contact.uploadImage(resource)

                        // 拼接公式前的文本
                        append(content, index, matcher.start())
                        // 插入图片
                        append(image)
                        // 移动索引
                        index = matcher.end()
                    } catch (ex: Throwable) {
                        logger.warning("处理LaTeX表达式时异常", ex)
                    }
                }
            }
            // 拼接后续消息
            append(content, index, content.length)
        }
    }

    /**
     * 工具列表
     */
    private val myTools = listOf(
        WebSearch(),
        WeatherService(),
        CrazyKfc(),
        IpAddressQuery(),
    )


    private suspend fun chatCompletion(
        chatMessages: List<ChatMessage>,
        hasTools: Boolean = true
    ): ChatMessage {
        val openAi = this.openAi ?: throw NullPointerException("OpenAI Token 未设置，无法开始")
        val availableTools = if (hasTools) {
            myTools.filter { it.isEnabled }.map { it.tool }
        } else null
        val request = ChatCompletionRequest(
            model = ModelId(PluginConfig.chatModel),
            messages = chatMessages,
            tools = availableTools,
            toolChoice = ToolChoice.Auto
        )
        logger.info(
            "API Requesting..." +
                    " Model=${PluginConfig.chatModel}" +
                    " Tools=${availableTools?.joinToString(prefix = "[", postfix = "]")}"
        )
        val response = openAi.chatCompletion(request)
        val message = response.choices.first().message
        logger.info("Response: $message ${response.usage}")
        return message
    }

    private fun MessageChain.plainText() = this.filterIsInstance<PlainText>().joinToString().trim()

    private suspend fun ToolCall.Function.execute(event: MessageEvent): String {
        val agent = myTools.find { it.tool.function.name == function.name }
            ?: return "Function ${function.name} not found"
        // 提示正在执行函数
        val receipt = if (agent.loadingMessage.isNotEmpty()) {
            event.subject.sendMessage(event.message.quote() + agent.loadingMessage)
        } else null
        // 提取参数
        val args = function.argumentsAsJsonOrNull()
        logger.info("Calling ${function.name}(${args})")
        // 执行函数
        val result = try {
            agent.execute(args)
        } catch (e: Throwable) {
            logger.error("Failed to call ${function.name}", e)
            "工具调用失败，请尝试自行回答用户，或如实告知。"
        }
        logger.info("Result=$result")
        // 过会撤回加载消息
        if (receipt != null) {
            launch { delay(3.seconds); receipt.recall() }
        }
        return result
    }

}