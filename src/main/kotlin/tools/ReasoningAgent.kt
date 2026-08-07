package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.StreamOptions
import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import com.aallam.openai.api.core.Usage
import com.aallam.openai.api.model.ModelId
import kotlinx.serialization.json.*
import net.mamoe.mirai.event.events.MessageEvent
import top.jie65535.mirai.data.ModelUsageRecorder
import top.jie65535.mirai.llm.LargeLanguageModels
import top.jie65535.mirai.llm.ModelService

class ReasoningAgent : BaseAgent(
    tool = Tool.function(
        name = "reasoning",
        description = "可通过调用推理模型对问题进行深度思考。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("prompt") {
                    put("type", "string")
                    put("description", "用于调用推理模型的提示词")
                }
            }
            putJsonArray("required") {
                add("prompt")
            }
        },
    )
) {
    override val loadingMessage: String
        get() = "思考中..."

    override val isEnabled: Boolean
        get() = LargeLanguageModels.reasoning != null

    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        requireNotNull(args)
        val endpoint = LargeLanguageModels.reasoning ?: return "未配置llm，无法进行推理。"

        val prompt = args.getValue("prompt").jsonPrimitive.content
        val answerContent = StringBuilder()
        val reasoningContent = StringBuilder()
        var lastUsage: Usage? = null
        var cacheUsage: ModelService.CacheUsage? = null
        endpoint.service.chatCompletions(
            ChatCompletionRequest(
                model = ModelId(endpoint.model),
                messages = listOf(ChatMessage.User(prompt)),
                streamOptions = StreamOptions(includeUsage = true),
            )
        ) { cacheUsage = it }.collect {
            it.usage?.let { usage -> lastUsage = usage }
            if (it.choices.isNotEmpty()) {
                val delta = it.choices[0].delta ?: return@collect
                if (!delta.reasoningContent.isNullOrEmpty()) {
                    reasoningContent.append(delta.reasoningContent)
                }
                if (!delta.content.isNullOrEmpty()) {
                    answerContent.append(delta.content)
                }
            }
        }

        val result = answerContent.toString()
        val reasoning = reasoningContent.toString()
        ModelUsageRecorder.recordTokens(
            event = event,
            endpointLabel = "reasoning",
            modelAlias = endpoint.alias,
            provider = endpoint.provider,
            model = endpoint.model,
            usageKind = "reasoning",
            usage = lastUsage,
            cacheUsage = cacheUsage,
        )
        val output = when {
            result.isNotEmpty() -> result
            reasoning.isNotEmpty() -> reasoning
            else -> "推理出错，结果为空"
        }
        return output
    }
}
