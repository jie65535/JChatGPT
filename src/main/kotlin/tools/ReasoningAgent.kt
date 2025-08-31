package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import com.aallam.openai.api.model.ModelId
import kotlinx.serialization.json.*
import top.jie65535.mirai.LargeLanguageModels
import top.jie65535.mirai.PluginConfig

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

    override suspend fun execute(args: JsonObject?): String {
        requireNotNull(args)
        val llm = LargeLanguageModels.reasoning ?: return "未配置llm，无法进行推理。"

        val prompt = args.getValue("prompt").jsonPrimitive.content
        val answerContent = StringBuilder()
        llm.chatCompletions(ChatCompletionRequest(
            model = ModelId(PluginConfig.reasoningModel),
            messages = listOf(ChatMessage.User(prompt))
        )).collect {
            if (it.choices.isNotEmpty()) {
                val delta = it.choices[0].delta ?: return@collect
                if (!delta.content.isNullOrEmpty()) {
                    answerContent.append(delta.content)
                }
            }
        }

        return answerContent.toString().ifEmpty { "推理出错，结果为空" }
    }
}