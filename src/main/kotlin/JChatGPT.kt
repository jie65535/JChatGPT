package top.jie65535.mirai

import com.aallam.openai.api.chat.ChatCompletionChunk
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ToolCall
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
import xyz.cssxsh.mirai.hibernate.MiraiHibernateRecorder
import xyz.cssxsh.mirai.hibernate.entry.MessageRecord
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.collections.*
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.hours

object JChatGPT : KotlinPlugin(
    JvmPluginDescription(
        id = "top.jie65535.mirai.JChatGPT",
        name = "J ChatGPT",
        version = "1.9.0",
    ) {
        author("jie65535")
//        dependsOn("xyz.cssxsh.mirai.plugin.mirai-hibernate-plugin", true)
    }
) {
    /**
     * 是否包含历史对话
     */
    private var includeHistory: Boolean = false

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

        // 如果没有 @bot 或者 触发关键字 或者 回复bot的消息 则直接结束
        if (!event.message.contains(At(event.bot))
            && keyword?.let { event.message.content.contains(it) } != true
            && event.message[QuoteReply]?.source?.fromId != event.bot.id)
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
        if (event is GroupMessageEvent) {
            if (PluginConfig.enableFavorabilitySystem) {
                val favorabilityInfos = history.map { it.fromId }
                    .filter { it != event.bot.id }
                    .distinct()
                    .mapNotNull { PluginData.userFavorability[it] }
                if (favorabilityInfos.isNotEmpty()) {
                    historyText.appendLine("## 相关成员的好感信息")
                    for (info in favorabilityInfos) {
                        historyText.append(getNameCard(event.group, info.userId)).append('\t')
                            .appendLine(info).appendLine()
                    }
                    historyText.appendLine("---").appendLine()
                }
            }

            historyText.appendLine("## 近期群消息（更早已隐藏）")
            for (record in history) {
                // 同一人发言不要反复出现这人的名字，减少上下文
                appendGroupMessageRecord(historyText, record, event, lastId != record.fromId)
                lastId = record.fromId
            }
        } else {
            if (PluginConfig.enableFavorabilitySystem) {
                val favorabilityInfo = PluginData.userFavorability[event.sender.id]
                if (favorabilityInfo != null) {
                    historyText.append("你对\"").append(event.senderName).append("\"的好感信息如下: ")
                        .appendLine(favorabilityInfo).appendLine()
                    historyText.appendLine("---").appendLine()
                }
            }

            historyText.appendLine("## 近期对话（更早已隐藏）")
            for (record in history) {
                // 同一人发言不要反复出现这人的名字，减少上下文
                appendMessageRecord(historyText, record, event, lastId != record.fromId)
                lastId = record.fromId
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
        showSender: Boolean,
    ) {
        if (showSender) {
            // 名字前空行
            historyText.appendLine()
            // 名称显示
            if (event.bot.id == record.fromId) {
                historyText.append("**你** " + getNameCard(event.subject.botAsMember))
            } else {
                historyText.append(getNameCard(event.subject, record.fromId))
            }
            // 发言时间
            historyText.append(' ')
                .append(timeFormatter.format(Instant.ofEpochSecond(record.time.toLong())))
        }


        val recordMessage = record.toMessageChain()
        recordMessage[QuoteReply.Key]?.let {
            historyText.append(" 引用 ${getNameCard(event.subject, it.source.fromId)} 说的\n > ")
                .appendLine(it.source.originalMessage.content.replace("\n", "\n > "))
        }

        if (showSender) {
            // 消息内容
            historyText.append(" 说：")
        }

        historyText.appendLine(record.toMessageChain().joinToString("") {
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
        event: MessageEvent,
        showSender: Boolean
    ) {
        if (showSender) {
            if (event.bot.id == record.fromId) {
                historyText.append("**你** " + event.bot.nameCardOrNick)
            } else {
                historyText.append(event.senderName)
            }
            historyText
                .append(" ")
                // 发言时间
                .append(timeFormatter.format(Instant.ofEpochSecond(record.time.toLong())))
        }
        val recordMessage = record.toMessageChain()
        recordMessage[QuoteReply.Key]?.let {
            historyText.append(" 引用\n > ")
                .appendLine(it.source.originalMessage
                    .joinToString("", transform = ::singleMessageToText)
                    .replace("\n", "\n > "))
        }
        if (showSender) {
            historyText.append(" 说：")
        }
        // 消息内容
        historyText.appendLine(
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
                    "![${if (it.isEmoji) "表情包" else "图片"}]($imageUrl)"
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
        if (!requestMap.add(event.subject.id)) {
            logger.warning("The current Contact is busy!")
            return
        }

        try {
            val history = mutableListOf<ChatMessage>()

            val prompt = getSystemPrompt(event)
            if (PluginConfig.logPrompt) {
                logger.info("Prompt: $prompt")
            }
            history.add(ChatMessage(ChatRole.System, prompt))

            val historyText = getHistory(event)
            logger.info("History: $historyText")
            history.add(ChatMessage.User(historyText))


            var done: Boolean
            // 至少循环3次
            var retry = max(PluginConfig.retryMax, 3)
            do {
                try {
                    val startedAt = OffsetDateTime.now().toEpochSecond().toInt()
                    val responseFlow = chatCompletions(history)
                    var responseMessageBuilder: StringBuilder? = null
                    val responseToolCalls = mutableListOf<ToolCall.Function>()
                    val toolCallTasks = mutableListOf<Deferred<ChatMessage>>()
                    // 处理聊天流式响应
                    responseFlow.collect { chunk ->
                        val delta = chunk.choices[0].delta ?: return@collect

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
                    }

                    // 移除思考内容
                    val responseContent = responseMessageBuilder?.replace(thinkRegex, "")?.trim()
                    logger.info("LLM Response: $responseContent")
                    // 记录AI回答
                    history.add(ChatMessage.Assistant(
                        content = responseContent,
                        toolCalls = responseToolCalls
                    ))

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
                        done = history.any { it.name == "endConversation" }
                    } else {
                        done = true
                    }

                    if (!done) {
                        history.add(ChatMessage.User(
                            buildString {
                                appendLine("## 系统提示")
                                append("本次运行最多还剩").append(retry-1).appendLine("轮。")
                                appendLine("如果要多次发言，可以一次性调用多次发言工具。")
                                appendLine("如果没有什么要做的，可以提前结束。")
                                appendLine("当前时间：" + dateTimeFormatter.format(OffsetDateTime.now()))

                                val newMessages = getAfterHistory(startedAt, event)
                                if (newMessages.isNotEmpty()) {
                                    append("## 以下是上次运行至今的新消息\n\n$newMessages")
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
                        // event.subject.sendMessage("出错了...正在重试...")
                    }
                }
            } while (!done && 0 < --retry)
        } catch (ex: Throwable) {
            logger.warning(ex)
            event.subject.sendMessage("很抱歉，发生异常，请稍后重试")
        } finally {
            // 一段时间后才允许再次提问，防止高频对话
            launch {
                delay(1.seconds)
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
                t.add(MessageChunk(
                    it.range,
                    Image(url)))
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

        // 图像编辑模型
        ImageEdit(),

        // 天气服务
        WeatherService(),

        // 好感度调整
        AdjustUserFavorabilityAgent(),

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
        hasTools: Boolean = true
    ): Flow<ChatCompletionChunk> {
        val llm = LargeLanguageModels.chat ?: throw NullPointerException("OpenAI Token 未设置，无法开始")
        val availableTools = if (hasTools) {
            myTools.filter { it.isEnabled }.map { it.tool }
        } else null
        val request = ChatCompletionRequest(
            model = ModelId(PluginConfig.chatModel),
            temperature = PluginConfig.chatTemperature,
            messages = chatMessages,
            tools = availableTools,
        )
        logger.info("API Requesting... Model=${PluginConfig.chatModel}")
        return llm.chatCompletions(request)
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