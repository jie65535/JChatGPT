package top.jie65535.mirai

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.Chat
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
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.contact.MemberPermission.*
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
import xyz.cssxsh.mirai.hibernate.MiraiHibernateRecorder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import kotlin.collections.*
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object JChatGPT : KotlinPlugin(
    JvmPluginDescription(
        id = "top.jie65535.mirai.JChatGPT",
        name = "J ChatGPT",
        version = "1.5.0",
    ) {
        author("jie65535")
//        dependsOn("xyz.cssxsh.mirai.plugin.mirai-hibernate-plugin", true)
    }
) {
    private var llm: Chat? = null

    /**
     * 是否包含历史对话
     */
    private var includeHistory: Boolean = false

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

        // 检查消息记录插件是否存在
        includeHistory = try {
            MiraiHibernateRecorder
            true
        } catch (_: Throwable) {
            false
        }

        GlobalEventChannel.parentScope(this)
            .subscribeAlways<MessageEvent> { event -> onMessage(event) }

        logger.info { "Plugin loaded" }
    }

    fun updateOpenAiToken(token: String) {
        val timeout = PluginConfig.timeout.milliseconds
        llm = OpenAI(
            token,
            host = OpenAIHost(baseUrl = PluginConfig.openAiApi),
            timeout = Timeout(request = timeout, connect = timeout, socket = timeout),
            // logging = LoggingConfig(LogLevel.All)
        )
        reasoningAgent.llm = llm
    }

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneOffset.systemDefault())
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年MM月dd E HH:mm:ss")

    //    private val userContext = ConcurrentMap<Long, MutableList<ChatMessage>>()
    private const val REPLAY_QUEUE_MAX = 10
    private val replyMap = ConcurrentMap<Int, MutableList<ChatMessage>>(REPLAY_QUEUE_MAX)
    private val replyQueue = mutableListOf<Int>()
    private val requestMap = ConcurrentSet<Long>()

    private suspend fun onMessage(event: MessageEvent) {
        // 检查Token是否设置
        if (llm == null) return
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

        // 是否@bot
        val isAtBot = event.message.contains(At(event.bot))
        // 是否包含引用消息
        val quote = event.message[QuoteReply]
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

        startChat(event, context)
    }

    private fun getSystemPrompt(event: MessageEvent): String {
        val now = OffsetDateTime.now()
        val prompt = StringBuilder(PluginConfig.prompt)
        fun replace(target: String, replacement: () -> String) {
            val i = prompt.indexOf(target)
            if (i != -1) {
                prompt.replace(i, i + target.length, replacement())
            }
        }

        replace("{time}") {
            dateTimeFormatter.format(now)
        }

        replace("{subject}") {
            if (event is GroupMessageEvent) {
                "\"${event.subject.name}\" 群聊中"
            } else {
                "私聊中"
            }
        }

//        replace("{sender}") {
//            if (event is GroupMessageEvent) {
//                event.sender.specialTitle
//                val permissionName = when (event.sender.permission) {
//                    MEMBER -> "普通群员"
//                    ADMINISTRATOR -> "管理员"
//                    OWNER -> "群主"
//                }
//                "\"${event.senderName}\" 身份：$permissionName"
//            } else {
//                "\"${event.senderName}\""
//            }
//        }

        replace("{history}") {
            if (!includeHistory) {
                return@replace "暂无内容"
            }

            // 一段时间内的消息
            val beforeTimestamp = now.minusMinutes(PluginConfig.historyWindowMin.toLong()).toEpochSecond().toInt()
            val nowTimestamp = now.toEpochSecond().toInt()
            // 最近这段时间的历史对话
            val history = MiraiHibernateRecorder[event.subject, beforeTimestamp, nowTimestamp]
                .take(PluginConfig.historyMessageLimit) // 只取最近的部分消息，避免上下文过长
                .sortedBy { it.time } // 按时间排序
            // 构造历史消息
            val historyText = StringBuilder()
            if (event is GroupMessageEvent) {
                for (record in history) {
                    if (event.bot.id == record.fromId) {
                        historyText.append("你")
                    } else {
                        val recordSender = event.subject[record.fromId]
                        if (recordSender != null) {
                            // 群活跃等级
                            historyText.append(getNameCard(recordSender))
                        } else {
                            // 未知群员
                            historyText.append("未知群员(").append(record.fromId).append(")")
                        }
                    }
                    historyText
                        .append(" ")
                        // 发言时间
                        .append(timeFormatter.format(Instant.ofEpochSecond(record.time.toLong())))
                        // 消息内容
                        .append(" 说：").appendLine(record.toMessageChain().joinToString("") {
                            when (it) {
                                is At -> {
                                    it.getDisplay(event.subject)
                                }

                                is ForwardMessage -> {
                                    it.title + "\n" + it.preview
                                }

                                is QuoteReply -> {
                                    ">" + it.source.originalMessage.contentToString().replace("\n", "\n> ") + "\n"
                                }

                                else -> {
                                    it.contentToString()
                                }
                            }
                        })

                }
            } else {
                // TODO 私聊
            }

            historyText.toString()
        }

        return prompt.toString()
    }

    private suspend fun startChat(event: MessageEvent, context: List<ChatMessage>? = null) {
        val history = mutableListOf<ChatMessage>()
        if (!context.isNullOrEmpty()) {
            history.addAll(context)
        } else if (PluginConfig.prompt.isNotEmpty()) {
            val prompt = getSystemPrompt(event)
            if (PluginConfig.logPrompt) {
                logger.info("Prompt: $prompt")
            }
            history.add(ChatMessage(ChatRole.System, prompt))
        }
        val msg = event.message.plainText()
        if (msg.isNotEmpty()) {
            history.add(ChatMessage(ChatRole.User, if (event is GroupMessageEvent) {
                "${getNameCard(event.sender)} 说：$msg"
            } else {
                msg
            }))
        }

        try {
            if (!requestMap.add(event.sender.id)) {
                event.subject.sendMessage(event.message.quote() + "再等等...")
                return
            }

            var done = true
            // 至少重试两次
            var retry = max(PluginConfig.retryMax, 2)
            do {
                try {
                    val reply = chatCompletion(history, retry > 1)
                    history.add(reply)
                    done = true

                    for (toolCall in reply.toolCalls.orEmpty()) {
                        require(toolCall is ToolCall.Function) { "Tool call is not a function" }
                        val functionResponse = toolCall.execute(event)
                        history.add(
                            ChatMessage(
                                role = ChatRole.Tool,
                                toolCallId = toolCall.id,
                                name = toolCall.function.name,
                                content = functionResponse
                            )
                        )
                        done = false
                    }
                } catch (e: Exception) {
                    if (retry <= 1) {
                        throw e
                    } else {
                        logger.warning("调用llm时发生异常，重试中", e)
                        event.subject.sendMessage(event.message.quote() + "出错了...正在重试...")
                    }
                }
            } while (!done && 0 <-- retry)

            val content = history.last().content ?: "..."
            val replyMsg = event.subject.sendMessage(
                if (content.length < PluginConfig.messageMergeThreshold) {
                    event.message.quote() + toMessage(event.subject, content)
                } else {
                    // 消息内容太长则转为转发消息避免刷屏
                    event.buildForwardMessage {
                        event.bot says toMessage(event.subject, content)
                    }

                    // 不再将历史对话记录加入其中
//                    event.buildForwardMessage {
//                        for (item in history) {
//                            if (item.content.isNullOrEmpty())
//                                continue
//                            val temp = toMessage(event.subject, item.content!!)
//                            when (item.role) {
//                                Role.User -> event.sender says temp
//                                Role.Assistant -> event.bot says temp
//                            }
//                        }
//
//                        // 检查并移除超出转发消息上限的消息
//                        var isOverflow = false
//                        var count = 0
//                        for (i in size - 1 downTo 0) {
//                            if (count > 4900) {
//                                isOverflow = true
//                                // 删除早期上下文消息
//                                removeAt(i)
//                            } else {
//                                for (text in this[i].messageChain.filterIsInstance<PlainText>()) {
//                                    count += text.content.length
//                                }
//                            }
//                        }
//                        if (count > 5000) {
//                            removeAt(0)
//                        }
//                        if (isOverflow) {
//                            // 如果溢出了，插入一条提示到最开始
//                            add(
//                                0, ForwardMessage.Node(
//                                    senderId = event.bot.id,
//                                    time = this[0].time - 1,
//                                    senderName = event.bot.nameCardOrNick,
//                                    message = PlainText("更早的消息已隐藏，避免超出转发消息上限。")
//                                )
//                            )
//                        }
//                    }
                }
            )

            // 将回复的消息和对话历史保存到队列
            if (replyMsg.sourceIds.isNotEmpty()) {
                val msgId = replyMsg.sourceIds[0]
                replyMap[msgId] = history
                replyQueue.add(msgId)
            }
            // 移除超出队列的对话
            if (replyQueue.size > REPLAY_QUEUE_MAX) {
                replyMap.remove(replyQueue.removeAt(0))
            }
        } catch (ex: Throwable) {
            logger.warning(ex)
            event.subject.sendMessage(event.message.quote() + "很抱歉，发生异常，请稍后重试")
        } finally {
            requestMap.remove(event.sender.id)
        }
//        catch (ex: OpenAITimeoutException) {
//            event.subject.sendMessage(event.message.quote() + "很抱歉，服务器没响应，请稍后重试")
//        }
    }

    private val laTeXPattern = Pattern.compile(
        "\\\\\\((.+?)\\\\\\)|" + // 匹配行内公式 \(...\)
                "\\\\\\[(.+?)\\\\\\]|" + // 匹配独立公式 \[...\]
                "\\$\\$([^$]+?)\\$\\$|" + // 匹配独立公式 $$...$$
                "\\$\\s(.+?)\\s\\$|" + // 匹配行内公式 $...$
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

    private val reasoningAgent = ReasoningAgent()

    /**
     * 工具列表
     */
    private val myTools = listOf(
        // 网页搜索
        WebSearch(),

        // 运行代码
        RunCode(),

        // 推理代理
        reasoningAgent,

        // 天气服务
        WeatherService(),

        // IP所在地查询 暂时取消，几乎不会用到
        // IpAddressQuery(),

        // Epic 免费游戏
        EpicFreeGame(),
    )


    private suspend fun chatCompletion(
        chatMessages: List<ChatMessage>,
        hasTools: Boolean = true
    ): ChatMessage {
        val llm = this.llm ?: throw NullPointerException("OpenAI Token 未设置，无法开始")
        val availableTools = if (hasTools) {
            myTools.filter { it.isEnabled }.map { it.tool }
        } else null
        val request = ChatCompletionRequest(
            model = ModelId(PluginConfig.chatModel),
            messages = chatMessages,
            tools = availableTools,
        )
        logger.info("API Requesting... Model=${PluginConfig.chatModel}"
//                    " Tools=${availableTools?.joinToString(prefix = "[", postfix = "]")}"
        )
        val response = llm.chatCompletion(request)
        val message = response.choices.first().message
        logger.info("Response: $message ${response.usage}")
        return message
    }

    private fun getNameCard(member: Member): String {
        val nameCard = StringBuilder()
        // 群活跃等级
        nameCard.append("【lv").append(member.active.temperature).append(" ")
        // 群头衔
        if (member.specialTitle.isNotEmpty()) {
            nameCard.append(member.specialTitle)
        } else {
            nameCard.append(
                when (member.permission) {
                    OWNER -> "群主"
                    ADMINISTRATOR -> "管理员"
                    MEMBER -> member.temperatureTitle
                }
            )
        }
        // 群名片
        nameCard.append("】 ").append(member.nameCardOrNick)
        // .append(" (").append(recordSender.id).append(")")
        return nameCard.toString()
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
        logger.info("Result=\"$result\"")
        // 过会撤回加载消息
        if (receipt != null) {
            launch {
                delay(3.seconds)
                try {
                    receipt.recall()
                } catch (e: Throwable) {
                    logger.error("消息撤回失败，调试信息：" +
                            "source.internalIds=${receipt.source.internalIds.joinToString()} " +
                            "source.ids= ${receipt.source.ids.joinToString()}", e)
                }
            }
        }
        return result
    }

}