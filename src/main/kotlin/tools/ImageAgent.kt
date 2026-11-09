package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import net.mamoe.mirai.event.events.MessageEvent
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.config.PluginConfig
import top.jie65535.mirai.data.ModelUsageRecorder
import top.jie65535.mirai.llm.ModelCatalog
import top.jie65535.mirai.media.*

class ImageAgent : BaseAgent(
    Tool.function(
        name = "imageAgent",
        description = "生成或编辑图片，支持聊天图片与素材库参考图；不传参考图即文生图。" +
            "提示词应说明各参考图用途、修改项与保留项；GIF参考图使用首帧。同步绘图可能需要几分钟，结果不会自动发送或收藏；" +
            "发送用返回的 Markdown，收藏用 imageLibrary。失败时如实告知，不要自动重试或换模型重画。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                ImageReferences.schema(this, ImageGenerationProtocol.MAX_REFERENCES)
                putJsonObject("prompt") { put("type", "string"); put("maxLength", 32000); put("description", "生成时必填的画面或编辑要求") }
                putJsonObject("size") { put("type", "string"); put("description", "OpenAI 兼容接口可选：auto 或 WIDTHxHEIGHT，例如 1024x1024") }
                putJsonObject("quality") { put("type", "string"); put("description", "OpenAI 兼容接口可选质量，须为所配置模型支持的档位") }
                putJsonObject("background") {
                    put("type", "string"); putJsonArray("enum") { listOf("auto", "opaque", "transparent").forEach { add(it) } }
                }
            }
            putJsonArray("required") { add("prompt") }
        },
    )
) {
    companion object {
        const val API_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
    }

    override val retryOnConnectionFailure: Boolean = false
    override val followRedirects: Boolean = false
    override val isEnabled: Boolean get() = ModelCatalog.resolveImage() != null && ImageAssets.store != null
    override val loadingMessage: String get() = "作图处理中，可能需要等待几分钟..."

    private val service by lazy {
        ImageGenerationService(httpClient, ImageAssets.requireStore(),
            download = { url ->
                ImageBinary.inspect(ImageAssets.downloader.downloadOriginal(url, ImageBinary.MAX_BYTES, 120_000))
            },
            // 上游异常可能含鉴权 URL；日志只记录失败阶段和异常类型。
            report = { message, _ -> JChatGPT.logger.warning(message) })
    }

    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        requireNotNull(args)
        val model = ModelCatalog.resolveImage() ?: return "未正确配置图像模型，无法生成图片。"
        val prompt = args["prompt"]?.jsonPrimitive?.content
        require(!prompt.isNullOrBlank()) { "生成图片需要 prompt" }
        val sources = ImageReferences.fromArguments(args, ImageGenerationProtocol.MAX_REFERENCES) {
            JChatGPT.lookupImageUrl(event.subject.id, it)
        }
        require(model.providerType != "dashscope" || sources.size <= 3) { "千问原生接口最多支持 3 张参考图" }
        val images = mutableListOf<ImageBinary>()
        sources.forEach { source ->
            val original = ImageAssets.read(source)
            images += withContext(Dispatchers.IO) { original.forEditing() }
            require(images.sumOf { it.bytes.size.toLong() } <= 100L * 1024 * 1024) { "参考图总大小超过 100 MiB" }
        }
        val options = buildJsonObject {
            listOf("size", "quality", "background").forEach { key -> args[key]?.let { put(key, it) } }
        }
        val result = service.generate(model, prompt, images, sources.map { it.reference }, options,
            PluginConfig.imageWatermark, PluginConfig.imageTimeoutMillis.coerceIn(30_000L, 1_800_000L))
        if (result.generatedImages > 0) {
            ModelUsageRecorder.recordUnits(event, "image", model.alias, model.provider, model.model,
                "image", "images", images.size.toLong(), result.generatedImages.toLong())
        }
        return buildJsonObject {
            put("message", result.error ?: "图片已保存。可通过原有消息工具发送 Markdown，或自行选择收藏；请复用已有结果。")
            putJsonArray("images") {
                result.assets.forEach { asset -> addJsonObject {
                    put("image", asset.reference); put("markdown", asset.markdown)
                    asset.width?.let { put("width", it) }; asset.height?.let { put("height", it) }
                    put("collected", asset.collected)
                } }
            }
        }.toString()
    }
}
