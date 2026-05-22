package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
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

class ImageAgent : BaseAgent(
    tool = Tool.function(
        name = "imageAgent",
        description = "调用千问图像模型生成或编辑图片。不传 image_urls 即纯文生图；" +
                "传 1~3 张图片可进行编辑、修改或多图融合。" +
                "备注：该方法成本较高，非必要尽量不要调用。" +
                "编辑图片前无需识别图片内容，模型自己会理解图片内容。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("image_urls") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "string")
                    }
                    put("description", "参考图片地址列表，可传 0~3 张。" +
                            "不传或为空即纯文生图；传 1 张为编辑；多张为融合，输出比例与最后一张对齐。")
                }
                putJsonObject("prompt") {
                    put("type", "string")
                    put("description", "提示词，描述期望生成或修改的画面内容。")
                }
            }
            putJsonArray("required") {
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
        get() = "作图中..."

    override suspend fun execute(args: JsonObject?): String {
        requireNotNull(args)
        val prompt = args.getValue("prompt").jsonPrimitive.content
        val imageUrls = args["image_urls"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?: emptyList()

        val response = httpClient.post(API_URL) {
            contentType(ContentType("application", "json"))
            header("Authorization", "Bearer " + PluginConfig.dashScopeApiKey)
            setBody(buildJsonObject {
                put("model", PluginConfig.imageModel)
                putJsonObject("input") {
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                for (url in imageUrls) {
                                    addJsonObject {
                                        put("image", url)
                                    }
                                }
                                addJsonObject {
                                    put("text", prompt)
                                }
                            }
                        }
                    }
                }
                putJsonObject("parameters") {
                    put("n", 1)
                    put("prompt_extend", true)
                    put("watermark", PluginConfig.imageWatermark)
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
            "图片已生成，发送时请务必包含完整的url和查询参数，因为下载地址存在鉴权：![图片]($url)"
        } catch (e: Throwable) {
            JChatGPT.logger.error("图像生成结果解析异常", e)
            responseJson
        }
    }
}
