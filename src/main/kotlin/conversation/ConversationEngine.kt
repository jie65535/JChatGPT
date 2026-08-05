package top.jie65535.mirai.conversation

import com.aallam.openai.api.chat.ChatCompletionChunk
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.core.Usage
import com.aallam.openai.api.model.ModelId
import io.ktor.util.collections.ConcurrentSet
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
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.config.PluginConfig
import top.jie65535.mirai.data.TokenUsageStore
import top.jie65535.mirai.llm.LargeLanguageModels
import top.jie65535.mirai.llm.ModelService
import top.jie65535.mirai.profile.ProfileAutoMaintenance
import top.jie65535.mirai.tools.AdjustUserFavorabilityAgent
import top.jie65535.mirai.tools.BaseAgent
import top.jie65535.mirai.tools.DeleteSkill
import top.jie65535.mirai.tools.GroupManageAgent
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
import top.jie65535.mirai.util.RetryBackoff
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal object ConversationEngine {
    private val activeRequests = ConcurrentSet<Long>()
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
        QueryUserProfileAgent(),
        WebSearch(),
        VisitWeb(),
        RunCode(),
        ReasoningAgent(),
        VisualAgent(),
        ImageAgent(),
        WeatherService(),
        AdjustUserFavorabilityAgent(),
        RequestOwner(),
        GroupManageAgent(),
    )

    fun clear() {
        activeRequests.clear()
    }

    suspend fun start(event: MessageEvent) {
        val subjectId = event.subject.id
        if (!activeRequests.add(subjectId)) {
            JChatGPT.logger.warning("The current Contact is busy!")
            return
        }

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

            if (history.isEmpty() || cache == null) {
                val prompt = ConversationContext.getSystemPrompt(event)
                if (PluginConfig.logPrompt) JChatGPT.logger.info("Prompt: $prompt")
                history += ChatMessage(ChatRole.System, prompt)
                val historyText = ConversationContext.getHistory(event)
                JChatGPT.logger.info("注入聊天记录：\n$historyText")
                history += ChatMessage.User(historyText)
            } else {
                val newMessages = ConversationContext.getAfterHistory(cache.lastActivityAt, event)
                JChatGPT.logger.info("补充聊天记录：\n$newMessages")
                history += ChatMessage.User("## 以下是上次对话结束至今的新消息\n\n$newMessages")
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
                        val delta = chunk.choices[0].delta ?: return@collect
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
                                        toolCall.toResultMessage(event)
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
                        chunk.usage?.let { lastTokenUsage = it }
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
                    recordUsage(event, lastTokenUsage, lastCacheUsage)
                    completedRounds++

                    if (responseToolCalls.size > toolCallTasks.size) {
                        val finalToolResult = responseToolCalls.last().toResultMessage(event)
                        if (toolCallTasks.isNotEmpty()) history += toolCallTasks.awaitAll()
                        history += finalToolResult
                        done = responseToolCalls.any { it.function.name == "endConversation" }
                    } else {
                        done = true
                    }

                    if (!done) {
                        history += ChatMessage.User(
                            buildContinuationPrompt(maxRounds - completedRounds, startedAt, event)
                        )
                    } else {
                        if (PluginConfig.enableContextCache) {
                            ConversationContext.saveCache(
                                subjectId,
                                ConversationCache(history, startedAt, replyIndex, imageIndex),
                            )
                            JChatGPT.logger.debug("已保存对话上下文到缓存")
                        }
                        if (event is GroupMessageEvent) {
                            ProfileAutoMaintenance.recordCompletedConversation(event, startedAt)
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
            event.subject.sendMessage("很抱歉，发生异常，请稍后重试")
        } finally {
            ConversationContext.releaseActiveIndexes(subjectId)
            JChatGPT.launch {
                delay(500.milliseconds)
                activeRequests.remove(subjectId)
            }
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

    private fun buildContinuationPrompt(remainingRounds: Int, startedAt: Int, event: MessageEvent): String = buildString {
        appendLine("## 系统提示")
        append("本次运行最多还剩").append(remainingRounds).appendLine("轮。")
        appendLine("如果要多次发言，可以一次性调用多次发言工具。")
        appendLine("如果没有什么要做的，可以提前结束。")
        appendLine("当前时间：${dateTimeFormatter.format(OffsetDateTime.now())}")
        val messages = ConversationContext.getAfterHistory(startedAt, event)
        if (messages.isNotEmpty()) append("## 以下是上次运行至今的新消息\n\n$messages")
    }

    private fun recordUsage(
        event: MessageEvent,
        usage: Usage?,
        cacheUsage: ModelService.CacheUsage?,
    ) {
        usage ?: return
        val group = (event as? GroupMessageEvent)?.group
        TokenUsageStore.record(
            timestamp = OffsetDateTime.now().toEpochSecond(),
            userId = event.sender.id,
            userNickname = event.senderName,
            groupId = group?.id,
            groupName = group?.name,
            promptTokens = usage.promptTokens ?: 0,
            completionTokens = usage.completionTokens ?: 0,
            totalTokens = usage.totalTokens ?: 0,
            cachedTokens = cacheUsage?.hitTokens ?: 0,
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
