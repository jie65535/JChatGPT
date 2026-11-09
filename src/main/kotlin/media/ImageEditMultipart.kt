package top.jie65535.mirai.media

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.quote
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** 与绘图技能一致的文件表单：字段名加引号，不附加分段 Content-Length，原图直接写入请求。 */
internal class ImageEditMultipart(payload: JsonObject, images: List<ImageBinary>) : OutgoingContent.WriteChannelContent() {
    private data class Part(val headers: ByteArray, val bytes: ByteArray)

    private val boundary = "image-" + UUID.randomUUID().toString().replace("-", "")
    override val contentType = ContentType.MultiPart.FormData.withParameter("boundary", boundary)
    private val parts = buildList {
        val fields = (listOf("model", "prompt", "n", "size", "quality", "background", "output_format") + payload.keys).distinct()
        fields.forEach { key ->
            val value = payload[key] ?: return@forEach
            require(key !in setOf("image", "image[]", "mask")) { "参考文件须通过 images 参数传入" }
            require(key.isNotBlank() && key.none { it < ' ' || it == '\u007f' }) { "绘图表单字段名无效" }
            add(Part("--$boundary\r\nContent-Disposition: form-data; name=${key.quote()}\r\n\r\n".toByteArray(),
                (if (value is JsonPrimitive) value.content else value.toString()).toByteArray()))
        }
        images.forEachIndexed { index, image ->
            val name = if (images.size == 1) "image" else "image[]"
            val headers = "--$boundary\r\nContent-Disposition: form-data; name=${name.quote()}; " +
                "filename=\"input-$index.${image.format}\"\r\nContent-Type: ${image.mimeType}\r\n\r\n"
            add(Part(headers.toByteArray(), image.bytes))
        }
    }
    private val ending = "--$boundary--\r\n".toByteArray()
    private val newLine = byteArrayOf(13, 10)
    override val contentLength: Long = parts.sumOf { it.headers.size.toLong() + it.bytes.size + newLine.size } + ending.size

    override suspend fun writeTo(channel: ByteWriteChannel) {
        try {
            parts.forEach { part ->
                channel.writeFully(part.headers)
                channel.writeFully(part.bytes)
                channel.writeFully(newLine)
            }
            channel.writeFully(ending)
        } catch (cause: Throwable) {
            channel.close(cause)
            throw cause
        } finally {
            channel.flushAndClose()
        }
    }
}
