package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ContentPart
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.TextPart
import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import com.aallam.openai.api.model.ModelId
import io.ktor.client.plugins.ClientRequestException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.mamoe.mirai.event.events.MessageEvent
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.config.PluginConfig
import top.jie65535.mirai.llm.LargeLanguageModels
import java.net.URI

class VisualAgent : BaseAgent(
    tool = Tool.function(
        name = "imageRecognition",
        description = "可通过调用视觉模型识别一张或多张图片，并进行比较、关联或顺序理解。备注：该方法成本较高，非必要尽量不要调用。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("image_indices") {
                    put("type", "array")
                    put("description", "用户消息中[图片n]或[表情包n]标记的图片编号数组，按需要理解的顺序传入")
                    put("minItems", 1)
                    put("maxItems", MAX_SOURCE_IMAGES)
                    putJsonObject("items") {
                        put("type", "integer")
                        put("minimum", 1)
                    }
                }
                putJsonObject("prompt") {
                    put("type", "string")
                    put("description", "用于调用视觉模型的提示词")
                }
            }
            putJsonArray("required") {
                add("image_indices")
                add("prompt")
            }
        }
    )
) {
    private val imageResolver = VisualImageResolver()
    private val concurrencyLimiter = Semaphore(VISUAL_MAX_CONCURRENCY)

    override val loadingMessage: String
        get() = "识别中..."

    override val isEnabled: Boolean
        get() = LargeLanguageModels.visual != null

    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        requireNotNull(args)
        val llm = LargeLanguageModels.visual ?: return "未配置llm，无法进行识别。"
        val imageIndices = args["image_indices"]?.jsonArray
            ?.map { it.jsonPrimitive.int }
            ?.ifEmpty { null }
            ?: throw IllegalArgumentException("至少需要提供一张图片")
        require(imageIndices.size <= MAX_SOURCE_IMAGES) { "单次最多处理 $MAX_SOURCE_IMAGES 张用户图片" }
        val imageUrls = imageIndices.map { imageIndex ->
            JChatGPT.lookupImageUrl(event.subject.id, imageIndex)
                ?: throw IllegalArgumentException("图片编号[$imageIndex]不存在或已失效")
        }
        val prompt = args.getValue("prompt").jsonPrimitive.content

        return concurrencyLimiter.withPermit {
            val imageGroups = imageUrls.mapIndexed { index, imageUrl ->
                if (PluginConfig.visualImageBase64Enabled) {
                    val host = runCatching { URI(imageUrl).host }.getOrNull() ?: "unknown"
                    val resolved = try {
                        imageResolver.resolve(imageUrl)
                    } catch (e: Throwable) {
                        JChatGPT.logger.error(
                            "视觉图片下载失败: image=${imageIndices[index]}, url=$imageUrl"
                        )
                        throw e
                    }
                    val mimeTypes = resolved.images.map { it.mimeType }.distinct().joinToString()
                    JChatGPT.logger.info(
                        "视觉图片已本地化: image=${imageIndices[index]}, source=${index + 1}/${imageUrls.size}, host=$host, " +
                                "parts=${resolved.images.size}, mime=$mimeTypes, " +
                                "sourceBytes=${resolved.sourceSize}, payloadChars=${resolved.payloadSize}, " +
                                "transcoded=${resolved.transcoded}"
                    )
                    PreparedImageGroup(
                        inputs = resolved.images.map { it.dataUrl },
                        orderHint = resolved.orderHint,
                        payloadSize = resolved.payloadSize,
                    )
                } else {
                    PreparedImageGroup(inputs = listOf(imageUrl), orderHint = null, payloadSize = 0)
                }
            }
            val modelImageCount = imageGroups.sumOf { it.inputs.size }
            val totalPayloadSize = imageGroups.sumOf { it.payloadSize }
            require(modelImageCount <= MAX_MODEL_IMAGES) {
                "图片及长图切片共 $modelImageCount 张，超过单次工程限制 $MAX_MODEL_IMAGES 张"
            }
            require(totalPayloadSize <= MAX_TOTAL_PAYLOAD_CHARS) {
                "图片 Base64 总大小超过 ${MAX_TOTAL_PAYLOAD_CHARS / 1_000_000}MB 工程限制"
            }
            val messageContent = buildMessageContent(imageGroups, prompt)

            val maxAttempts = PluginConfig.visualRetryMax.coerceIn(1, 3)
            var lastError: Throwable? = null
            repeat(maxAttempts) { attempt ->
                try {
                    val answerContent = StringBuilder()
                    llm.chatCompletions(
                        ChatCompletionRequest(
                            model = ModelId(PluginConfig.visualModel),
                            messages = listOf(
                                ChatMessage.User(
                                    content = messageContent
                                )
                            )
                        )
                    ).collect {
                        if (it.choices.isNotEmpty()) {
                            val delta = it.choices[0].delta ?: return@collect
                            if (!delta.content.isNullOrEmpty()) {
                                answerContent.append(delta.content)
                            }
                        }
                    }

                    if (answerContent.isNotEmpty()) {
                        return@withPermit answerContent.toString()
                    }
                    throw IllegalStateException("识图异常，结果为空")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (!isRetryable(e)) throw e
                    lastError = e
                    if (attempt + 1 < maxAttempts) {
                        JChatGPT.logger.warning(
                            "视觉模型调用失败，将进行第 ${attempt + 2}/$maxAttempts 次尝试",
                            e
                        )
                        delay(RETRY_BASE_DELAY_MILLIS * (attempt + 1L))
                    }
                }
            }

            throw lastError ?: IllegalStateException("视觉模型调用失败")
        }
    }

    companion object {
        private const val VISUAL_MAX_CONCURRENCY = 2
        private const val RETRY_BASE_DELAY_MILLIS = 800L
        private const val MAX_SOURCE_IMAGES = 16
        private const val MAX_MODEL_IMAGES = 32
        private const val MAX_TOTAL_PAYLOAD_CHARS = 48_000_000

        private data class PreparedImageGroup(
            val inputs: List<String>,
            val orderHint: String?,
            val payloadSize: Int,
        )

        private fun buildMessageContent(groups: List<PreparedImageGroup>, prompt: String): List<ContentPart> {
            if (groups.size == 1 && groups[0].inputs.size == 1) {
                return listOf(ImagePart(groups[0].inputs[0]), TextPart(prompt))
            }

            return buildList {
                groups.forEachIndexed { groupIndex, group ->
                    add(
                        TextPart(
                            "用户图片 ${groupIndex + 1}/${groups.size}" +
                                    if (group.inputs.size > 1) "，已切分为 ${group.inputs.size} 张连续切片：" else "："
                        )
                    )
                    group.inputs.forEach { add(ImagePart(it)) }
                    group.orderHint?.let { add(TextPart(it)) }
                }
                add(TextPart("请结合以上所有用户图片回答：$prompt"))
            }
        }

        private fun isRetryable(error: Throwable): Boolean {
            if (error is ClientRequestException) {
                return error.response.status.value in setOf(408, 409, 425, 429)
            }
            return true
        }
    }
}
