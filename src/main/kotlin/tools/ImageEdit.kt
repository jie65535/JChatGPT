package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.PluginConfig

class ImageEdit : BaseAgent(
    tool = Tool.function(
        name = "imageEdit",
        description = "可通过调用图像编辑模型来修改图片。备注：该方法成本较高，非必要尽量不要调用。编辑图片前无需识别图片内容，图像编辑模型自己会理解图片内容！",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("image_url") {
                    put("type", "string")
                    put("description", "原始图片地址")
                }
                putJsonObject("prompt") {
                    put("type", "string")
                    put("description", "正向提示词，用来描述需要对图片进行修改的要求。")
                }
                putJsonObject("negative_prompt") {
                    put("type", "string")
                    put("description", "反向提示词，用来描述不希望在画面中看到的内容，可以对画面进行限制。" +
                            "示例值：低分辨率、错误、最差质量、低质量、残缺、多余的手指、比例不良等。")
                }
            }
            putJsonArray("required") {
                add("image_url")
                add("prompt")
            }
        }
    )
) {
    companion object {
        const val API_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
    }

    override val isEnabled: Boolean
        get() = PluginConfig.dashScopeApiKey.isNotEmpty()

    override val loadingMessage: String
        get() = "图片编辑中..."

    override suspend fun execute(args: JsonObject?): String {
        requireNotNull(args)
        val imageUrl = args.getValue("image_url").jsonPrimitive.content
        val prompt = args.getValue("prompt").jsonPrimitive.content
        val negativePrompt = args["negative_prompt"]?.jsonPrimitive?.content
        val response = httpClient.post(API_URL) {
            contentType(ContentType("application", "json"))
            header("Authorization", "Bearer " + PluginConfig.dashScopeApiKey)
            setBody(buildJsonObject {
                put("model", PluginConfig.imageEditModel)
                putJsonObject("input") {
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                addJsonObject {
                                    put("image", imageUrl)
                                }
                                addJsonObject {
                                    put("text", prompt)
                                }
                            }
                        }
                    }
                }
                if (negativePrompt != null) {
                    putJsonObject("parameters") {
                        put("negative_prompt", negativePrompt)
                    }
                }
            }.toString())
        }

        val responseJson = response.bodyAsText()
        val responseObject = Json.parseToJsonElement(responseJson).jsonObject
        return try {
            val url = responseObject
                .getValue("output").jsonObject
                .getValue("choices").jsonArray[0].jsonObject
                .getValue("message").jsonObject
                .getValue("content").jsonArray[0].jsonObject
                .getValue("image").jsonPrimitive.content
            "图片已编辑完成，发送时请务必包含完整的url和查询参数，因为下载地址存在鉴权。图片地址：$url"
        } catch (e: Exception) {
            JChatGPT.logger.error("图像编辑结果解析异常", e)
            responseObject.toString()
        }
    }
}