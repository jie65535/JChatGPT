package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ContentPart
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.StreamOptions
import com.aallam.openai.api.chat.TextPart
import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import com.aallam.openai.api.core.Usage
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.mamoe.mirai.event.events.MessageEvent
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.config.PluginConfig
import top.jie65535.mirai.data.ModelUsageRecorder
import top.jie65535.mirai.llm.LargeLanguageModels
import top.jie65535.mirai.llm.ModelService
import top.jie65535.mirai.util.RetryBackoff
import top.jie65535.mirai.media.ImageAssets
import top.jie65535.mirai.media.ImageReferences
import top.jie65535.mirai.media.ImageSource

class VisualAgent : BaseAgent(
    tool = Tool.function(
        name = "imageRecognition",
        description = "查看聊天图片或 imageLibrary 素材库中的一张或多张图片，支持比较、关联或顺序理解。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                ImageReferences.schema(this, MAX_SOURCE_IMAGES)
                putJsonObject("prompt") {
                    put("type", "string")
                    put("description", "用于调用视觉模型的提示词")
                }
            }
            putJsonArray("required") {
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

    override fun close() {
        imageResolver.close()
        super.close()
    }

    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        requireNotNull(args)
        val endpoint = LargeLanguageModels.visual ?: return "未配置llm，无法进行识别。"
        val sources = ImageReferences.fromArguments(args, MAX_SOURCE_IMAGES) {
            JChatGPT.lookupImageUrl(event.subject.id, it)
        }
        require(sources.isNotEmpty()) { "至少需要提供一张图片" }
        val prompt = args.getValue("prompt").jsonPrimitive.content

        return concurrencyLimiter.withPermit {
            val imageGroups = sources.mapIndexed { index, source ->
                val resolved = try {
                    prepareImage(source, PluginConfig.visualImageBase64Enabled, imageResolver)
                } catch (e: Throwable) {
                    JChatGPT.logger.error(
                        "视觉图片读取失败: image=${source.reference}"
                    )
                    throw e
                }
                if (resolved != null) {
                    val mimeTypes = resolved.images.map { it.mimeType }.distinct().joinToString()
                    JChatGPT.logger.info(
                        "视觉图片已本地化: image=${source.reference}, source=${index + 1}/${sources.size}, " +
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
                    PreparedImageGroup(inputs = listOf((source as ImageSource.Chat).url), orderHint = null, payloadSize = 0)
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
            val retryBackoff = RetryBackoff.fromConfig()
            var lastError: Throwable? = null
            repeat(maxAttempts) { attempt ->
                try {
                    val answerContent = StringBuilder()
                    var lastUsage: Usage? = null
                    var cacheUsage: ModelService.UsageDetails? = null
                    endpoint.service.chatCompletions(
                        ChatCompletionRequest(
                            model = ModelId(endpoint.model),
                            messages = listOf(
                                ChatMessage.User(
                                    content = messageContent
                                )
                            ),
                            streamOptions = StreamOptions(includeUsage = true),
                        )
                    ) { cacheUsage = it }.collect {
                        it.usage?.let { usage -> lastUsage = usage }
                        if (it.choices.isNotEmpty()) {
                            val delta = it.choices[0].delta ?: return@collect
                            if (!delta.content.isNullOrEmpty()) {
                                answerContent.append(delta.content)
                            }
                        }
                    }

                    ModelUsageRecorder.recordTokens(
                        event = event,
                        endpointLabel = "visual",
                        modelAlias = endpoint.alias,
                        provider = endpoint.provider,
                        model = endpoint.model,
                        usageKind = "visual",
                        usage = lastUsage,
                        cacheUsage = cacheUsage,
                    )
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
                        val retryDelayMillis = retryBackoff.delayMillis(attempt + 1)
                        JChatGPT.logger.warning(
                            "视觉模型调用失败，将在 ${retryDelayMillis}ms 后进行第 ${attempt + 2}/$maxAttempts 次尝试",
                            e
                        )
                        if (retryDelayMillis > 0) delay(retryDelayMillis)
                    }
                }
            }

            throw lastError ?: IllegalStateException("视觉模型调用失败")
        }
    }

    companion object {
        private const val VISUAL_MAX_CONCURRENCY = 2
        private const val MAX_SOURCE_IMAGES = 16
        private const val MAX_MODEL_IMAGES = 32
        private const val MAX_TOTAL_PAYLOAD_CHARS = 48_000_000

        /** 只有远程 HTTP 图片可以直接交给模型拉取；内嵌图片和素材必须转换为 Data URL。 */
        internal suspend fun prepareImage(
            source: ImageSource,
            localizeRemote: Boolean,
            resolver: VisualImageResolver,
        ): VisualImageResolver.Result? {
            if (source is ImageSource.Chat && !source.isInline) {
                return if (localizeRemote) resolver.resolve(source.url) else null
            }
            val image = ImageAssets.read(source)
            return withContext(Dispatchers.IO) { resolver.prepare(image.bytes, image.mimeType) }
        }

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
