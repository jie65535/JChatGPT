package top.jie65535.mirai.conversation

import com.aallam.openai.api.chat.ChatCompletionChunk
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolChoice
import com.aallam.openai.api.chat.StreamOptions
import com.aallam.openai.api.core.Usage
import com.aallam.openai.api.model.ModelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.message.data.source
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.config.PluginConfig
import top.jie65535.mirai.data.ModelUsageRecorder
import top.jie65535.mirai.llm.LargeLanguageModels
import top.jie65535.mirai.llm.ModelService
import top.jie65535.mirai.profile.ProfileAutoMaintenance
import top.jie65535.mirai.tools.AdjustUserFavorabilityAgent
import top.jie65535.mirai.tools.BaseAgent
import top.jie65535.mirai.tools.DeleteSkill
import top.jie65535.mirai.tools.GroupManageAgent
import top.jie65535.mirai.tools.GetChatHistoryContext
import top.jie65535.mirai.tools.GithubAgent
import top.jie65535.mirai.tools.ImageAgent
import top.jie65535.mirai.tools.LoadSkill
import top.jie65535.mirai.tools.MemoryAppend
import top.jie65535.mirai.tools.MemoryReplace
import top.jie65535.mirai.tools.QueryUserProfileAgent
import top.jie65535.mirai.tools.ReasoningAgent
import top.jie65535.mirai.tools.RequestOwner
import top.jie65535.mirai.tools.RunCode
import top.jie65535.mirai.tools.SaveSkill
import top.jie65535.mirai.tools.SearchChatHistory
import top.jie65535.mirai.tools.SendCompositeMessage
import top.jie65535.mirai.tools.SendLaTeXExpression
import top.jie65535.mirai.tools.SendSingleMessageAgent
import top.jie65535.mirai.tools.SendVoiceMessage
import top.jie65535.mirai.tools.StopLoopAgent
import top.jie65535.mirai.tools.VisitWeb
import top.jie65535.mirai.tools.VisualAgent
import top.jie65535.mirai.tools.WeatherService
import top.jie65535.mirai.tools.WebSearch
import top.jie65535.mirai.tools.QueryTokenUsageAgent
import top.jie65535.mirai.util.RetryBackoff
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

internal object ConversationEngine {
    private val runtimeState = ConversationRuntimeState<MessageEvent>()
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年MM月dd E HH:mm:ss")
    private val thinkRegex = Regex("<think>[\\s\\S]*?</think>")
    private val tools: List<BaseAgent> = listOf(
        SendSingleMessageAgent(),
        SendCompositeMessage(),
        SendVoiceMessage(),
        SendLaTeXExpression(),
        StopLoopAgent(),
        MemoryAppend(),
        MemoryReplace(),
        LoadSkill(),
        SaveSkill(),
        DeleteSkill(),
        SearchChatHistory(),
        GetChatHistoryContext(),
        QueryUserProfileAgent(),
        WebSearch(),
        GithubAgent(),
        VisitWeb(),
        RunCode(),
        ReasoningAgent(),
        VisualAgent(),
        ImageAgent(),
        WeatherService(),
        AdjustUserFavorabilityAgent(),
        RequestOwner(),
        GroupManageAgent(),
        QueryTokenUsageAgent(),
    )

    fun clear() {
        runtimeState.clear()
    }

    fun isExpectedUser(event: MessageEvent): Boolean = runtimeState.isExpectedUser(
        key = event.toConversationKey(),
        userId = event.sender.id,
        nowEpochSecond = currentEpochSecond(),
    )

    suspend fun resumeObserved(event: MessageEvent): Boolean {
        val started = runtimeState.beginObserved(
            key = event.toConversationKey(),
            userId = event.sender.id,
            nowEpochSecond = currentEpochSecond(),
        ) ?: return false
        runConversation(event, started.running, started.resumedWait)
        return true
    }

    suspend fun start(event: MessageEvent) {
        when (val result = runtimeState.beginExplicit(event.toConversationKey(), event)) {
            is ConversationRuntimeState.BeginResult.Queued -> {
                if (result.newlyQueued) {
                    JChatGPT.logger.info(
                        "当前会话忙碌，已暂存用户 ${event.senderName}(${event.sender.id}) 的二次触发"
                    )
                } else {
                    JChatGPT.logger.info(
                        "当前会话已有待处理触发，用户 ${event.senderName}(${event.sender.id}) 的消息将通过增量历史合并"
                    )
                }
            }

            is ConversationRuntimeState.BeginResult.Started -> {
                runConversation(event, result.running, result.resumedWait)
            }
        }
    }

    private suspend fun runConversation(
        initialEvent: MessageEvent,
        running: ConversationRuntimeState.Running<MessageEvent>,
        resumedWait: FollowUpWaitDirective?,
    ) {
        val subjectId = initialEvent.subject.id
        var currentEvent = initialEvent
        var indexesReleased = false
        try {
            val cache = ConversationContext.cache(subjectId)
            val reuseCache = PluginConfig.enableContextCache && cache != null &&
                    !cache.isExpired(PluginConfig.contextCacheTimeoutMinutes * 60)
            val replyIndex = ConversationContext.activateReplyIndex(
                subjectId,
                cache?.replyIndex?.takeIf { reuseCache },
            )
            val imageIndex = ConversationContext.activateImageIndex(
                subjectId,
                cache?.imageIndex?.takeIf { reuseCache },
            )
            val history = if (reuseCache) {
                JChatGPT.logger.info("使用缓存的对话上下文，包含 ${cache.history.size} 条互动消息")
                cache.history
            } else mutableListOf()
            val profileInjectionState = cache?.profileInjectionState?.takeIf { reuseCache }
                ?: UserProfileInjectionState()

            if (history.isEmpty() || cache == null) {
                val prompt = ConversationContext.getSystemPrompt(currentEvent)
                if (PluginConfig.logPrompt) JChatGPT.logger.info("Prompt: $prompt")
                history += ChatMessage(ChatRole.System, prompt)
                val historyText = ConversationContext.getHistory(currentEvent, profileInjectionState)
                JChatGPT.logger.info("注入聊天记录：\n$historyText")
                history += ChatMessage.User(historyText)
            } else {
                val newMessages = ConversationContext.getAfterHistory(
                    time = cache.lastActivityAt,
                    event = currentEvent,
                    profileInjectionState = profileInjectionState,
                )
                JChatGPT.logger.info("补充聊天记录：\n$newMessages")
                history += ChatMessage.User(
                    if (resumedWait == null) {
                        "## 以下是上次对话结束至今的新消息\n\n$newMessages"
                    } else {
                        buildObservationResumePrompt(resumedWait, newMessages)
                    }
                )
            }
            if (resumedWait != null && !reuseCache) {
                history += ChatMessage.User(buildObservationResumePrompt(resumedWait, null))
            }

            val endpoints = LargeLanguageModels.orderedChatEndpoints()
            if (endpoints.isEmpty()) error("OpenAI Token 未设置，无法开始")
            var endpointIndex = 0
            var done: Boolean
            val maxRounds = PluginConfig.retryMax.coerceAtLeast(2)
            var completedRounds = 0
            val retryBackoff = RetryBackoff.fromConfig()
            var consecutiveFailures = 0
            do {
                val endpoint = endpoints[endpointIndex]
                val roundEvent = currentEvent
                var streamingOk = false
                try {
                    val startedAt = OffsetDateTime.now().toEpochSecond().toInt()
                    var lastCacheUsage: ModelService.CacheUsage? = null
                    val responseFlow = chatCompletions(history, endpoint) { lastCacheUsage = it }
                    var responseContent: StringBuilder? = null
                    var reasoningContent: StringBuilder? = null
                    val responseToolCalls = mutableListOf<ToolCall.Function>()
                    val toolCallTasks = mutableListOf<Deferred<ChatMessage>>()
                    var lastTokenUsage: Usage? = null

                    responseFlow.collect { chunk ->
                        chunk.usage?.let { lastTokenUsage = it }
                        val delta = chunk.choices.firstOrNull()?.delta ?: return@collect
                        delta.reasoningContent?.let { content ->
                            if (reasoningContent == null) reasoningContent = StringBuilder(content)
                            else reasoningContent.append(content)
                        }
                        delta.content?.let { content ->
                            if (responseContent == null) responseContent = StringBuilder(content)
                            else responseContent.append(content)
                        }
                        delta.toolCalls?.forEach { toolCallChunk ->
                            val index = toolCallChunk.index
                            val function = toolCallChunk.function
                            if (index >= responseToolCalls.size) {
                                responseToolCalls.lastOrNull()?.let { toolCall ->
                                    toolCallTasks += JChatGPT.async {
                                        toolCall.toResultMessage(roundEvent)
                                    }
                                }
                                val id = toolCallChunk.id
                                if (id != null && function != null) {
                                    responseToolCalls += ToolCall.Function(id, function)
                                }
                            } else if (function != null) {
                                val current = responseToolCalls[index]
                                var updated = current.function
                                function.nameOrNull?.let { name ->
                                    updated = updated.copy(nameOrNull = updated.nameOrNull.orEmpty() + name)
                                }
                                function.argumentsOrNull?.let { arguments ->
                                    updated = updated.copy(
                                        argumentsOrNull = updated.argumentsOrNull.orEmpty() + arguments
                                    )
                                }
                                responseToolCalls[index] = current.copy(function = updated)
                            }
                        }
                    }

                    streamingOk = true
                    LargeLanguageModels.reportSuccess(endpoint)
                    consecutiveFailures = 0
                    val answer = responseContent?.replace(thinkRegex, "")?.trim()
                    JChatGPT.logger.info("LLM Response: $answer")
                    history += ChatMessage(
                        role = ChatRole.Assistant,
                        content = answer,
                        toolCalls = responseToolCalls.ifEmpty { null },
                        reasoningContent = if (responseToolCalls.isNotEmpty()) reasoningContent?.toString() else null,
                    )
                    recordUsage(roundEvent, endpoint, lastTokenUsage, lastCacheUsage)
                    completedRounds++

                    if (responseToolCalls.size > toolCallTasks.size) {
                        val finalToolResult = responseToolCalls.last().toResultMessage(roundEvent)
                        if (toolCallTasks.isNotEmpty()) history += toolCallTasks.awaitAll()
                        history += finalToolResult
                    }

                    val endCalls = responseToolCalls.filter {
                        it.function.name == END_CONVERSATION_TOOL_NAME
                    }
                    if (endCalls.size > 1) {
                        JChatGPT.logger.warning("模型在同一轮调用了多次 endConversation，将采用第一次调用的参数")
                    }
                    val endCall = endCalls.firstOrNull()
                    val endArguments = endCall?.let { call ->
                        runCatching { call.function.argumentsAsJsonOrNull() }
                            .onFailure {
                                JChatGPT.logger.warning("无法解析 endConversation 参数，将按普通结束处理", it)
                            }
                            .getOrNull()
                    }
                    val waitDirective = parseFollowUpWait(endArguments)
                    if (endArguments?.containsKey(FOLLOW_UP_WAIT_ARGUMENT) == true && waitDirective == null) {
                        JChatGPT.logger.warning("endConversation.waitForFollowUp 参数无效，将按普通结束处理")
                    }

                    val requestedEnd = responseToolCalls.isEmpty() || endCall != null
                    val canContinue = completedRounds < maxRounds
                    if (!requestedEnd && canContinue) {
                        val pendingEvent = runtimeState.takePending(running)
                        if (pendingEvent != null) currentEvent = pendingEvent
                        history += ChatMessage.User(
                            buildContinuationPrompt(
                                remainingRounds = maxRounds - completedRounds,
                                startedAt = startedAt,
                                event = currentEvent,
                                pendingTrigger = pendingEvent != null,
                                profileInjectionState = profileInjectionState,
                            )
                        )
                        done = false
                    } else {
                        if (PluginConfig.enableContextCache) {
                            ConversationContext.saveCache(
                                subjectId,
                                ConversationCache(
                                    history = history,
                                    lastActivityAt = startedAt,
                                    replyIndex = replyIndex,
                                    imageIndex = imageIndex,
                                    profileInjectionState = profileInjectionState,
                                ),
                            )
                            JChatGPT.logger.debug("已保存对话上下文到缓存")
                        }

                        when (val finish = runtimeState.finish(
                            running = running,
                            waitDirective = waitDirective.takeIf { requestedEnd },
                            nowEpochSecond = currentEpochSecond(),
                            allowPendingContinuation = canContinue,
                            onFinished = {
                                ConversationContext.releaseActiveIndexes(subjectId)
                                indexesReleased = true
                            },
                        )) {
                            is ConversationRuntimeState.FinishResult.Continue -> {
                                currentEvent = finish.event
                                history += ChatMessage.User(
                                    buildContinuationPrompt(
                                        remainingRounds = maxRounds - completedRounds,
                                        startedAt = startedAt,
                                        event = currentEvent,
                                        pendingTrigger = true,
                                        profileInjectionState = profileInjectionState,
                                    )
                                )
                                done = false
                            }

                            is ConversationRuntimeState.FinishResult.Observing -> {
                                scheduleObservationTimeout(finish.observation)
                                (currentEvent as? GroupMessageEvent)?.let {
                                    ProfileAutoMaintenance.recordCompletedConversation(it, startedAt)
                                }
                                JChatGPT.logger.info(
                                    "会话已结束，等待用户 ${finish.observation.directive.fromUserIds.joinToString()} " +
                                            "在 ${finish.observation.directive.timeoutSeconds} 秒内发言"
                                )
                                done = true
                            }

                            ConversationRuntimeState.FinishResult.Ended -> {
                                (currentEvent as? GroupMessageEvent)?.let {
                                    ProfileAutoMaintenance.recordCompletedConversation(it, startedAt)
                                }
                                done = true
                            }
                        }
                    }
                } catch (cause: Exception) {
                    if (cause is CancellationException) throw cause
                    if (streamingOk) {
                        JChatGPT.logger.warning("调用llm后处理时发生异常，不再重试模型请求", cause)
                        throw cause
                    }
                    LargeLanguageModels.reportFailure(endpoint)
                    consecutiveFailures++
                    val nextEndpointIndex = nextChatEndpointIndex(
                        endpointCount = endpoints.size,
                        currentIndex = endpointIndex,
                        failureCount = consecutiveFailures,
                    )
                    if (nextEndpointIndex == null) {
                        JChatGPT.logger.warning(
                            "接入点[${endpoint.label}]调用失败，已无剩余接入点或重试次数",
                            cause,
                        )
                        throw cause
                    }
                    val nextEndpoint = endpoints[nextEndpointIndex]
                    val retryDelayMillis = retryBackoff.delayMillis(consecutiveFailures)
                    val retryMessage = if (nextEndpointIndex == endpointIndex) {
                        "接入点[${endpoint.label}]调用失败，将重试一次"
                    } else {
                        "接入点[${endpoint.label}]调用失败，将切换备用接入点[${nextEndpoint.label}]"
                    }
                    JChatGPT.logger.warning(
                        "$retryMessage，将在 ${retryDelayMillis}ms 后重试",
                        cause,
                    )
                    endpointIndex = nextEndpointIndex
                    if (retryDelayMillis > 0) delay(retryDelayMillis)
                    done = false
                }
            } while (!done && completedRounds < maxRounds)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            JChatGPT.logger.warning(cause)
            currentEvent.subject.sendMessage("很抱歉，发生异常，请稍后重试")
        } finally {
            if (!indexesReleased) {
                runtimeState.abort(running) {
                    ConversationContext.releaseActiveIndexes(subjectId)
                    indexesReleased = true
                }
            }
        }
    }

    private fun scheduleObservationTimeout(observation: ConversationRuntimeState.Observation) {
        val job = JChatGPT.launch {
            val remainingSeconds = observation.expiresAtEpochSecond - currentEpochSecond()
            if (remainingSeconds > 0) delay(remainingSeconds.seconds)
            if (runtimeState.expire(observation)) {
                JChatGPT.logger.debug(
                    "等待用户 ${observation.directive.fromUserIds.joinToString()} 的观察窗口已超时"
                )
            }
        }
        runtimeState.attachTimeoutJob(observation, job)
    }

    private fun MessageEvent.toConversationKey(): ConversationKey = ConversationKey(
        botId = bot.id,
        kind = message.source.kind,
        subjectId = subject.id,
    )

    private fun currentEpochSecond(): Long = OffsetDateTime.now().toEpochSecond()

    private fun buildObservationResumePrompt(
        directive: FollowUpWaitDirective,
        newMessages: String?,
    ): String = buildString {
        appendLine("## 观察状态恢复")
        appendLine("你此前结束发言后，选择等待指定用户在当前会话中的下一条消息。")
        append("等待用户：").appendLine(directive.fromUserIds.joinToString())
        append("等待条件：").appendLine(directive.condition)
        appendLine("被观察状态唤醒不代表必须回复。请判断新消息是否满足等待条件、是否承接当前话题。")
        appendLine("如果无关，不要发送任何内容，直接调用 endConversation。")
        if (newMessages != null) {
            appendLine()
            appendLine("## 等待后出现的新消息")
            append(newMessages)
        }
    }

    private fun chatCompletions(
        history: List<ChatMessage>,
        endpoint: LargeLanguageModels.ChatEndpoint,
        onCacheUsage: ((ModelService.CacheUsage) -> Unit)? = null,
    ): Flow<ChatCompletionChunk> {
        val availableTools = tools.filter { it.isEnabled }.map { it.tool }
        val request = ChatCompletionRequest(
            model = ModelId(endpoint.model),
            temperature = endpoint.temperature,
            messages = history,
            tools = availableTools,
            toolChoice = ToolChoice.Required,
            streamOptions = StreamOptions(includeUsage = true),
        )
        JChatGPT.logger.info("API Requesting... Model=${endpoint.model} [${endpoint.label}]")
        return endpoint.service.chatCompletions(request, onCacheUsage)
    }

    private suspend fun ToolCall.Function.toResultMessage(event: MessageEvent): ChatMessage = ChatMessage(
        role = ChatRole.Tool,
        toolCallId = id,
        name = function.name,
        content = execute(event),
    )

    private suspend fun ToolCall.Function.execute(event: MessageEvent): String {
        val agent = tools.find { it.tool.function.name == function.name }
            ?: return "Function ${function.name} not found"
        val receipt = if (PluginConfig.showToolCallingMessage && agent.loadingMessage.isNotEmpty()) {
            event.subject.sendMessage(agent.loadingMessage)
        } else null
        val result = try {
            val arguments = function.argumentsAsJsonOrNull()
            JChatGPT.logger.info("Calling ${function.name}($arguments)")
            agent.execute(arguments, event)
        } catch (cause: Throwable) {
            JChatGPT.logger.error("Failed to call ${function.name}", cause)
            "工具调用失败，请尝试自行回答用户，或如实告知。\n异常信息：${cause.message}"
        }
        JChatGPT.logger.info("Result=\"$result\"")
        val truncated = truncateToolOutput(result)
        if (truncated.length != result.length) {
            JChatGPT.logger.warning(
                "工具 ${function.name} 返回内容过长，已从 ${result.length} 字符截断至 ${truncated.length} 字符"
            )
        }
        if (receipt != null) {
            JChatGPT.launch {
                delay(3.seconds)
                try {
                    receipt.recall()
                } catch (cause: Throwable) {
                    JChatGPT.logger.error(
                        "消息撤回失败，调试信息：source.internalIds=${receipt.source.internalIds.joinToString()} " +
                                "source.ids=${receipt.source.ids.joinToString()}",
                        cause,
                    )
                }
            }
        }
        return truncated
    }

    private fun buildContinuationPrompt(
        remainingRounds: Int,
        startedAt: Int,
        event: MessageEvent,
        pendingTrigger: Boolean,
        profileInjectionState: UserProfileInjectionState,
    ): String = buildString {
        appendLine("## 系统提示")
        append("本次运行最多还剩").append(remainingRounds).appendLine("轮。")
        appendLine("如果要多次发言，可以一次性调用多次发言工具。")
        appendLine("如果没有什么要做的，可以提前结束。")
        if (pendingTrigger) appendLine("运行期间收到了新的显式触发，请优先处理水位后的新消息。")
        appendLine("当前时间：${dateTimeFormatter.format(OffsetDateTime.now())}")
        val messages = ConversationContext.getAfterHistory(
            time = startedAt,
            event = event,
            profileInjectionState = profileInjectionState,
        ).ifEmpty {
            if (pendingTrigger && !JChatGPT.includeHistory) {
                ConversationContext.getHistory(event, profileInjectionState)
            } else {
                ""
            }
        }
        if (messages.isNotEmpty()) append("## 以下是上次运行至今的新消息\n\n$messages")
    }

    private fun recordUsage(
        event: MessageEvent,
        endpoint: LargeLanguageModels.ChatEndpoint,
        usage: Usage?,
        cacheUsage: ModelService.CacheUsage?,
    ) {
        ModelUsageRecorder.recordTokens(
            event = event,
            endpointLabel = endpoint.label,
            modelAlias = endpoint.alias,
            provider = endpoint.provider,
            model = endpoint.model,
            usageKind = "chat",
            usage = usage,
            cacheUsage = cacheUsage,
        )
    }

    private fun truncateToolOutput(content: String): String {
        val maxLength = PluginConfig.maxToolOutputLength
        return if (content.length <= maxLength) content
        else content.take(maxLength) + "\n\n[系统提示：因内容过长，部分内容已被省略]"
    }
}

internal fun nextChatEndpointIndex(endpointCount: Int, currentIndex: Int, failureCount: Int): Int? {
    require(endpointCount > 0) { "endpointCount must be positive" }
    require(currentIndex in 0 until endpointCount) { "currentIndex must reference an endpoint" }
    require(failureCount > 0) { "failureCount must be positive" }
    return when {
        endpointCount == 1 && failureCount == 1 -> currentIndex
        currentIndex < endpointCount - 1 -> currentIndex + 1
        else -> null
    }
}
