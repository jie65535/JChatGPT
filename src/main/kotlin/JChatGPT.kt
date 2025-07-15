package top.jie65535.mirai

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.model.ModelId
import io.ktor.util.collections.*
import kotlinx.coroutines.delay
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
import net.mamoe.mirai.message.data.MessageSource.Key.quote
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import net.mamoe.mirai.utils.info
import top.jie65535.mirai.tools.*
import xyz.cssxsh.mirai.hibernate.MiraiHibernateRecorder
import xyz.cssxsh.mirai.hibernate.entry.MessageRecord
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.collections.*
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds

object JChatGPT : KotlinPlugin(
    JvmPluginDescription(
        id = "top.jie65535.mirai.JChatGPT",
        name = "J ChatGPT",
        version = "1.7.0",
    ) {
        author("jie65535")
//        dependsOn("xyz.cssxsh.mirai.plugin.mirai-hibernate-plugin", true)
    }
) {
    /**
     * 是否包含历史对话
     */
    private var includeHistory: Boolean = false

    val chatPermission = PermissionId("JChatGPT", "Chat")

    private var keyword: Regex? = null

    override fun onEnable() {
        // 注册聊天权限
        PermissionService.INSTANCE.register(chatPermission, "JChatGPT Chat Permission")
        PluginConfig.reload()
        PluginData.reload()

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

        logger.info { "Plugin loaded" }
    }

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneOffset.systemDefault())
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年MM月dd E HH:mm:ss")

    private val requestMap = ConcurrentSet<Long>()

    private suspend fun onMessage(event: MessageEvent) {
        // 检查Token是否设置
        if (LargeLanguageModels.chat == null) return
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

        // 如果没有@bot或者触发关键字则直接结束
        if (!event.message.contains(At(event.bot))
            && keyword?.let { event.message.content.contains(it) } != true)
            return

        startChat(event)
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
        // 构造历史消息
        val historyText = StringBuilder()
        if (event is GroupMessageEvent) {
            for (record in history) {
                appendGroupMessageRecord(historyText, record, event)
            }
        } else {
            for (record in history) {
                appendMessageRecord(historyText, record, event)
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
        event: GroupMessageEvent
    ) {
        if (event.bot.id == record.fromId) {
            historyText.append("**你** " + getNameCard(event.subject.botAsMember))
        } else {
            historyText.append(getNameCard(event.subject, record.fromId))
        }
        // 发言时间
        historyText.append(' ')
            .append(timeFormatter.format(Instant.ofEpochSecond(record.time.toLong())))
        val recordMessage = record.toMessageChain()
        recordMessage[QuoteReply.Key]?.let {
            historyText.append(" 引用 ${getNameCard(event.subject, it.source.fromId)} 说的\n > ")
                .appendLine(it.source.originalMessage.content.replace("\n", "\n > "))
        }
            // 消息内容
        historyText.append(" 说：").appendLine(record.toMessageChain().joinToString("") {
                when (it) {
                    is At -> {
                        it.getDisplay(event.subject)
                    }

                    else -> singleMessageToText(it)
                }
            })
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
        event: MessageEvent
    ) {
        if (event.bot.id == record.fromId) {
            historyText.append("**你** " + event.bot.nameCardOrNick)
        } else {
            historyText.append(event.senderName)
        }
        historyText
            .append(" ")
            // 发言时间
            .append(timeFormatter.format(Instant.ofEpochSecond(record.time.toLong())))
        val recordMessage = record.toMessageChain()
        recordMessage[QuoteReply.Key]?.let {
            historyText.append(" 引用\n > ")
                .appendLine(it.source.originalMessage
                    .joinToString("", transform = ::singleMessageToText)
                    .replace("\n", "\n > "))
        }
        // 消息内容
        historyText.append(" 说：").appendLine(
            record.toMessageChain().joinToString("", transform = ::singleMessageToText))
    }

    private fun singleMessageToText(it: SingleMessage): String {
        return when (it) {
            is ForwardMessage -> {
                it.title + "\n  " + it.preview
            }

            // 图片格式化
            is Image -> {
                try {
                    val imageUrl = runBlocking {
                        it.queryUrl()
                    }
                    "![图片]($imageUrl)"
                } catch (e: Throwable) {
                    logger.warning("图片地址获取失败", e)
                    it.content
                }
            }

            else -> it.content
        }
    }

    // endregion - 历史消息相关 -

    private val thinkRegex = Regex("<think>[\\s\\S]*?</think>")

    private suspend fun startChat(event: MessageEvent) {
        if (!requestMap.add(event.sender.id)) {
            event.subject.sendMessage("再等等...")
            return
        }

        val history = mutableListOf<ChatMessage>()
        if (PluginConfig.prompt.isNotEmpty()) {
            val prompt = getSystemPrompt(event)
            if (PluginConfig.logPrompt) {
                logger.info("Prompt: $prompt")
            }
            history.add(ChatMessage(ChatRole.System, prompt))
        }
        val historyText = getHistory(event)
        logger.info("History: $historyText")
        history.add(ChatMessage.User(historyText))

        try {
            var done: Boolean
            // 至少循环3次
            var retry = max(PluginConfig.retryMax, 3)
            do {
                try {
                    val startedAt = OffsetDateTime.now().toEpochSecond().toInt()
                    val response = chatCompletion(history)
                    // 移除思考内容
                    val responseContent = response.content?.replace(thinkRegex, "")?.trim()
                    history.add(ChatMessage.Assistant(
                        content = responseContent,
                        name = response.name,
                        toolCalls = response.toolCalls
                    ))

                    if (response.toolCalls.isNullOrEmpty()) {
                        done = true
                    } else {
                        done = false
                        // 处理函数调用
                        for (toolCall in response.toolCalls) {
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
                            if (toolCall.function.name == "endConversation") {
                                done = true
                            }
                        }
                    }

                    if (!done) {
                        history.add(ChatMessage.User(
                            buildString {
                                append("系统提示：本次运行还剩${retry-1}轮")

//                                if (response.toolCalls.isNullOrEmpty()) {
//                                    append("\n在上一轮对话中未检测到调用任何工具，请检查工具调用语法是否正确？")
//                                    append("\n如果你确实不需要调用其它工具比如发送消息，请调用`endConversation`来结束对话。")
//                                }

                                val newMessages = getAfterHistory(startedAt, event)
                                if (newMessages.isNotEmpty()) {
                                    append("\n以下是上次运行至今的新消息\n\n$newMessages")
                                }
                            }
                        ))
                    }
                } catch (e: Exception) {
                    if (retry <= 1) {
                        throw e
                    } else {
                        done = false
                        logger.warning("调用llm时发生异常，重试中", e)
                        event.subject.sendMessage(event.message.quote() + "出错了...正在重试...")
                    }
                }
            } while (!done && 0 < --retry)
        } catch (ex: Throwable) {
            logger.warning(ex)
            event.subject.sendMessage(event.message.quote() + "很抱歉，发生异常，请稍后重试")
        } finally {
            // 一段时间后才允许再次提问，防止高频对话
            launch {
                delay(5.seconds)
                requestMap.remove(event.sender.id)
            }
        }
    }

//    private suspend fun startChat(event: MessageEvent) {
//        if (!requestMap.add(event.sender.id)) {
//            // CD中不再引用消息，否则可能导致和机器人无限循环对话
//            event.subject.sendMessage("再等等...")
//            return
//        }
//
//        val history = mutableListOf<ChatMessage>()
//        if (PluginConfig.prompt.isNotEmpty()) {
//            val prompt = getSystemPrompt(event)
//            if (PluginConfig.logPrompt) {
//                logger.info("Prompt: $prompt")
//            }
//            history.add(ChatMessage(ChatRole.System, prompt))
//        }
//        val historyText = getHistory(event)
//        logger.info("History: $historyText")
//        history.add(ChatMessage.User(historyText))
//
//        try {
//            var done = true
//            // 至少重试两次
//            var retry = max(PluginConfig.retryMax, 3)
//            val finalToolCalls = mutableMapOf<Int, ToolCall.Function>()
//            val contentBuilder = StringBuilder()
//            do {
//                finalToolCalls.clear()
//                contentBuilder.setLength(0)
//
//                try {
//                    var sent = false
//                    // 流式处理响应
//                    withTimeout(PluginConfig.timeout) {
//                        chatCompletions(history, retry > 1).collect { chunk ->
//                            val delta = chunk.choices[0].delta
//                            if (delta == null) return@collect
//
//                            // 处理工具调用
//                            val toolCalls = delta.toolCalls
//                            if (toolCalls != null) {
//                                for (toolCall in toolCalls) {
//                                    val index = toolCall.index
//                                    val toolId = toolCall.id
//                                    val function = toolCall.function
//                                    // 取出未完成的函数调用
//                                    val incompleteCall = finalToolCalls[index]
//                                    // 如果是新的函数调用，保存起来
//                                    if (incompleteCall == null && toolId != null && function != null) {
//                                        // 添加函数调用
//                                        finalToolCalls[index] = ToolCall.Function(toolId, function)
//                                    } else if (incompleteCall != null && function != null && function.argumentsOrNull != null) {
//                                        // 更新参数内容
//                                        finalToolCalls[index] = incompleteCall.copy(
//                                            function = incompleteCall.function.copy(
//                                                argumentsOrNull = incompleteCall.function.arguments + function.arguments
//                                            )
//                                        )
//                                    }
//                                }
//                            }
//
//                            // 处理响应内容
//                            val contentChunk = delta.content
//                            // 避免连续发送多次，只拆分第一次进行发送
//                            if (contentChunk != null && !sent) {
//                                // 填入内容
//                                contentBuilder.append(contentChunk)
//                                sent = parseStreamingContent(contentBuilder, event)
//                            }
//                        }
//                    }
//
//                    val lastBlock = contentBuilder.toString().trim()
//                    if (lastBlock.isNotEmpty()) {
//                        event.subject.sendMessage(
//                            if (lastBlock.length > PluginConfig.messageMergeThreshold) {
//                                event.buildForwardMessage {
//                                    event.bot says toMessage(event.subject, lastBlock)
//                                }
//                            } else {
//                                toMessage(event.subject, lastBlock)
//                            }
//                        )
//                    }
//
//                    if (finalToolCalls.isNotEmpty()) {
//                        val toolCalls = finalToolCalls.values.toList()
//                        history.add(ChatMessage.Assistant(toolCalls = toolCalls))
//                        for (toolCall in toolCalls) {
//                            val functionResponse = toolCall.execute(event)
//                            history.add(
//                                ChatMessage(
//                                    role = ChatRole.Tool,
//                                    toolCallId = toolCall.id,
//                                    name = toolCall.function.name,
//                                    content = functionResponse
//                                )
//                            )
//                            done = false
//                        }
//                    } else {
//                        done = true
//                    }
//                } catch (e: Exception) {
//                    if (retry <= 1) {
//                        throw e
//                    } else {
//                        done = false
//                        logger.warning("调用llm时发生异常，重试中", e)
//                        event.subject.sendMessage(event.message.quote() + "出错了...正在重试...")
//                    }
//                }
//            } while (!done && 0 < --retry)
//        } catch (ex: Throwable) {
//            logger.warning(ex)
//            event.subject.sendMessage(event.message.quote() + "很抱歉，发生异常，请稍后重试")
//        } finally {
//            // 一段时间后才允许再次提问，防止高频对话
//            launch {
//                delay(10.seconds)
//                requestMap.remove(event.sender.id)
//            }
//        }
//    }

//    /**
//     * 解析流消息
//     */
//    private fun parseStreamingContent(contentBuilder: StringBuilder, event: MessageEvent): Boolean {
//        // 处理推理内容
//        val thinkBeginAt = contentBuilder.indexOf("<think")
//        if (thinkBeginAt >= 0) {
//            val thinkEndAt = contentBuilder.indexOf("</think>")
//            if (thinkEndAt > 0) {
//                // 去除思考内容
//                contentBuilder.delete(thinkBeginAt, thinkEndAt + "</think>".length)
//            }
//            // 跳过本轮处理
//            return false
//        }
//
//        // 处理代码块
//        val codeBlockBeginAt = contentBuilder.indexOf("```")
//        if (codeBlockBeginAt >= 0) {
//            val codeBlockEndAt = contentBuilder.indexOf("```", codeBlockBeginAt + 3)
//            if (codeBlockEndAt >= 0) {
//                val codeBlockContentBegin = contentBuilder.indexOf("\n", codeBlockBeginAt + 3)
//                if (codeBlockContentBegin in codeBlockBeginAt..codeBlockEndAt) {
//                    val codeBlockContent = contentBuilder.substring(codeBlockContentBegin, codeBlockEndAt).trim()
//                    contentBuilder.delete(codeBlockBeginAt, codeBlockEndAt + 3)
//                    launch {
//                        // 发送代码块内容
//                        event.subject.sendMessage(
//                            if (codeBlockContent.length < PluginConfig.messageMergeThreshold) {
//                                toMessage(event.subject, codeBlockContent)
//                            } else {
//                                // 消息内容太长则转为转发消息避免刷屏
//                                event.buildForwardMessage {
//                                    event.bot says toMessage(event.subject, codeBlockContent)
//                                }
//                            }
//                        )
//                    }
//                }
//            }
//            // 跳过本轮处理
//            return true
//        }
//
//        // 徒手trimStart
//        var contentBeginAt = 0
//        while (contentBeginAt < contentBuilder.length) {
//            if (contentBuilder[contentBeginAt].isWhitespace()) {
//                contentBeginAt++
//            } else {
//                break
//            }
//        }
//
//        // 对空行进行分割输出
//        val emptyLineAt = contentBuilder.indexOf("\n\n", contentBeginAt)
//        if (emptyLineAt > 0) {
//            val lineContent = contentBuilder.substring(contentBeginAt, emptyLineAt)
//            contentBuilder.delete(0, emptyLineAt + 2)
//            launch {
//                // 发送消息内容
//                event.subject.sendMessage(toMessage(event.subject, lineContent))
//            }
//            return true
//        }
//        return false
//    }


    private val regexAtQq = Regex("@(\\d+)")

    private val regexLaTeX = Regex(
        "\\\\\\((.+?)\\\\\\)|" + // 匹配行内公式 \(...\)
                "\\\\\\[(.+?)\\\\\\]|" + // 匹配独立公式 \[...\]
                "\\$\\s(.+?)\\s\\$|" // 匹配行内公式 $...$
    )

    private data class MessageChunk(val range: IntRange, val content: Message)

    /**
     * 将聊天内容转为聊天消息，如果聊天中包含LaTeX表达式，将会转为图片拼接到消息中。
     *
     * @param contact 联系对象
     * @param content 文本内容
     * @return 构造的消息
     */
    suspend fun toMessage(contact: Contact, content: String): Message {
        return if (content.isEmpty()) {
            PlainText("...")
        } else if (content.length < 3) {
            PlainText(content)
        } else {
            val t = mutableListOf<MessageChunk>()
            regexAtQq.findAll(content).forEach {
                val qq = it.groups[1]?.value?.toLongOrNull()
                if (qq != null && contact is Group) {
                    contact[qq]?.let { member -> t.add(MessageChunk(it.range, At(member))) }
                }
            }

            regexLaTeX.findAll(content).forEach {
                it.groups.forEach { group ->
                    if (group == null || group.value.isEmpty()) return@forEach
                    try {
                        // 将所有匹配的LaTeX公式转为图片拼接到消息中
                        val formula = group.value
                        val imageByteArray = LaTeXConverter.convertToImage(formula, "png")
                        val resource = imageByteArray.toExternalResource("png")
                        val image = contact.uploadImage(resource)

                        t.add(MessageChunk(group.range, image))
                    } catch (ex: Throwable) {
                        logger.warning("处理LaTeX表达式时异常", ex)
                    }
                }
            }

            buildMessageChain {
                var index = 0
                for ((range, msg) in t.sortedBy { it.range.start }) {
                    if (index < range.start) {
                        append(content, index, range.start)
                    }
                    append(msg)
                    index = range.endInclusive + 1
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

        // 记忆代理
        MemoryAgent(),

        // 结束循环
        StopLoopAgent(),

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

        // 天气服务
        WeatherService(),

        // Epic 免费游戏
        EpicFreeGame(),
    )


//    private fun chatCompletions(
//        chatMessages: List<ChatMessage>,
//        hasTools: Boolean = true
//    ): Flow<ChatCompletionChunk> {
//        val llm = this.llm ?: throw NullPointerException("OpenAI Token 未设置，无法开始")
//        val availableTools = if (hasTools) {
//            myTools.filter { it.isEnabled }.map { it.tool }
//        } else null
//        val request = ChatCompletionRequest(
//            model = ModelId(PluginConfig.chatModel),
//            messages = chatMessages,
//            tools = availableTools,
//        )
//        logger.info("API Requesting... Model=${PluginConfig.chatModel}")
//        return llm.chatCompletions(request)
//    }

    private suspend fun chatCompletion(
        chatMessages: List<ChatMessage>,
        hasTools: Boolean = true
    ): ChatMessage {
        val llm = LargeLanguageModels.chat ?: throw NullPointerException("OpenAI Token 未设置，无法开始")
        val availableTools = if (hasTools) {
            myTools.filter { it.isEnabled }.map { it.tool }
        } else null
        val request = ChatCompletionRequest(
            model = ModelId(PluginConfig.chatModel),
            messages = chatMessages,
            tools = availableTools,
        )
        logger.info("API Requesting... Model=${PluginConfig.chatModel}")
        val response = llm.chatCompletion(request)
        val message = response.choices.first().message
        logger.info("Response: $message ${response.usage}")
        return message
    }

    private fun getNameCard(member: Member): String {
        val nameCard = StringBuilder()
        // 群活跃等级
        nameCard.append("【lv").append(member.active.temperature).append(" ")
        try {
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
        } catch (e: Throwable) {
            logger.warning("获取群头衔失败", e)
        }
        // 群名片
        nameCard.append("】 ").append(member.nameCardOrNick).append("(").append(member.id).append(")")
        return nameCard.toString()
    }


    private suspend fun ToolCall.Function.execute(event: MessageEvent): String {
        val agent = myTools.find { it.tool.function.name == function.name }
            ?: return "Function ${function.name} not found"
        // 提示正在执行函数
        val receipt = if (agent.loadingMessage.isNotEmpty()) {
            event.subject.sendMessage(agent.loadingMessage)
        } else null
        // 提取参数
        val args = function.argumentsAsJsonOrNull()
        logger.info("Calling ${function.name}(${args})")
        // 执行函数
        val result = try {
            agent.execute(args, event)
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
                    logger.error(
                        "消息撤回失败，调试信息：" +
                                "source.internalIds=${receipt.source.internalIds.joinToString()} " +
                                "source.ids= ${receipt.source.ids.joinToString()}", e
                    )
                }
            }
        }
        return result
    }

}