package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.TextPart
import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import com.aallam.openai.api.model.ModelId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import top.jie65535.mirai.LargeLanguageModels
import top.jie65535.mirai.PluginConfig

class VisualAgent : BaseAgent(
    tool = Tool.function(
        name = "imageRecognition",
        description = "可通过调用视觉模型来识别图片内容。备注：该方法成本较高，非必要尽量不要调用。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("image_url") {
                    put("type", "string")
                    put("description", "图片地址")
                }
                putJsonObject("prompt") {
                    put("type", "string")
                    put("description", "用于调用视觉模型的提示词")
                }
            }
            putJsonArray("required") {
                add("image_url")
                add("prompt")
            }
        }
    )
) {
    override val loadingMessage: String
        get() = "识别中..."

    override val isEnabled: Boolean
        get() = LargeLanguageModels.visual != null

    override suspend fun execute(args: JsonObject?): String {
        requireNotNull(args)
        val llm = LargeLanguageModels.visual ?: return "未配置llm，无法进行识别。"
        val imageUrl = args.getValue("image_url").jsonPrimitive.content
        val prompt = args.getValue("prompt").jsonPrimitive.content

        val answerContent = StringBuilder()
        llm.chatCompletions(ChatCompletionRequest(
            model = ModelId(PluginConfig.visualModel),
            messages = listOf(
                ChatMessage.System("You are a helpful assistant."),
                ChatMessage.User(
                    content = listOf(
                        ImagePart(imageUrl),
                        TextPart(prompt)
                    )
                )
            )
        )).collect {
            if (it.choices.isNotEmpty()) {
                val delta = it.choices[0].delta ?: return@collect
                if (!delta.content.isNullOrEmpty()) {
                    answerContent.append(delta.content)
                }
            }
        }
        return answerContent.toString().ifEmpty { "识图异常，结果为空" }
    }
}