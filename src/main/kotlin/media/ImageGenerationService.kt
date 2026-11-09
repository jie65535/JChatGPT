package top.jie65535.mirai.media

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import top.jie65535.mirai.data.ImageAsset
import top.jie65535.mirai.data.ImageAssetStore
import top.jie65535.mirai.llm.ResolvedModelDefinition
import java.io.ByteArrayOutputStream
import java.util.Base64

internal data class ImageGenerationResult(
    val assets: List<ImageAsset>,
    val generatedImages: Int = 0,
    val error: String? = null,
)

/** 同步调用绘图接口并保存图片；每次仅提交一次，失败时直接返回原因。 */
internal class ImageGenerationService(
    private val client: HttpClient,
    private val assets: ImageAssetStore,
    private val download: suspend (String) -> ImageBinary,
    private val report: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val semaphore = Semaphore(1)

    suspend fun generate(
        model: ResolvedModelDefinition,
        prompt: String,
        images: List<ImageBinary>,
        references: List<String>,
        options: JsonObject,
        watermark: Boolean,
        timeoutMillis: Long,
    ): ImageGenerationResult = semaphore.withPermit {
        val payload = ImageGenerationProtocol.payload(model, prompt, images, options, watermark)
        val endpoint = ImageGenerationProtocol.endpoint(model, images.isNotEmpty())
        val saved = mutableListOf<ImageAsset>()
        var generatedImages = 0
        try {
            val response = client.post(endpoint) {
                header("Authorization", "Bearer ${model.token}")
                header("Accept", "application/json")
                timeout {
                    requestTimeoutMillis = timeoutMillis
                    socketTimeoutMillis = timeoutMillis
                    connectTimeoutMillis = 30_000
                }
                if (model.providerType != "dashscope" && images.isNotEmpty()) {
                    setBody(ImageGenerationProtocol.multipart(payload, images))
                } else {
                    contentType(ContentType.Application.Json)
                    setBody(payload.toString())
                }
            }
            val status = response.status.value
            if (status !in 200..299) {
                response.bodyAsChannel().cancel()
                val reason = if (status in 400..499) "站点拒绝了绘图请求" else "绘图结果无法确认，上游可能已生成或计费"
                return@withPermit ImageGenerationResult(emptyList(), error =
                    "$reason（HTTP $status）。请如实告知，不要自动重试或换模型重画。")
            }
            val outputs = ImageGenerationProtocol.parseResponse(
                Json.parseToJsonElement(readResponse(response.bodyAsChannel())).jsonObject,
                model.providerType == "dashscope",
            )
            generatedImages = outputs.size
            outputs.forEachIndexed { index, output ->
                val image = if (!output.base64.isNullOrBlank()) {
                    require(output.base64.length <= (ImageBinary.MAX_BYTES.toLong() + 2) / 3 * 4) { "返回图片超过 50 MiB" }
                    withContext(Dispatchers.IO) { ImageBinary.inspect(Base64.getDecoder().decode(output.base64)) }
                } else download(requireNotNull(output.url))
                saved += withContext(Dispatchers.IO) {
                    assets.save(image, "生成图片-${System.currentTimeMillis()}-${index + 1}", "generated",
                        model = model.model, prompt = prompt, references = references)
                }
            }
            ImageGenerationResult(saved, generatedImages)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            val reason = if (generatedImages > 0) "图片已生成，但下载或保存失败" else "绘图结果无法确认，上游可能已生成或计费"
            report("$reason（${cause.javaClass.simpleName}），未自动重试", cause)
            ImageGenerationResult(saved, generatedImages, "$reason。请如实告知，不要自动重试或换模型重画；已保存的图片可直接复用。")
        }
    }

    private suspend fun readResponse(channel: io.ktor.utils.io.ByteReadChannel): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val count = channel.readAvailable(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= 90L * 1024 * 1024) { "绘图响应超过 90 MiB" }
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            channel.cancel()
        }
    }
}
