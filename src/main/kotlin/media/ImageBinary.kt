package top.jie65535.mirai.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/** 原始文件与模型输入分开处理，收藏时保留动画和透明通道。 */
internal data class ImageBinary(
    val bytes: ByteArray,
    val format: String,
    val width: Int?,
    val height: Int?,
) {
    val mimeType: String get() = "image/${if (format == "jpg") "jpeg" else format}"
    fun dataUrl(): String = "data:$mimeType;base64," + Base64.getEncoder().encodeToString(bytes)

    fun forEditing(): ImageBinary {
        if (format != "gif") return this
        val frame = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("无法读取 GIF 首帧")
        val output = ByteArrayOutputStream()
        check(ImageIO.write(frame, "png", output)) { "无法转换 GIF 参考图" }
        return inspect(output.toByteArray())
    }

    companion object {
        const val MAX_BYTES = 50 * 1024 * 1024

        fun inspect(bytes: ByteArray): ImageBinary {
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "图片为空或超过 50 MiB" }
            fun starts(vararg values: Int) = bytes.size >= values.size &&
                values.indices.all { bytes[it].toInt() and 255 == values[it] }
            fun ascii(offset: Int, length: Int) = if (bytes.size >= offset + length)
                String(bytes, offset, length, Charsets.US_ASCII) else ""
            val format = when {
                starts(137, 80, 78, 71, 13, 10, 26, 10) -> "png"
                starts(255, 216, 255) -> "jpg"
                ascii(0, 6) in setOf("GIF87a", "GIF89a") -> "gif"
                ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> "webp"
                else -> throw IllegalArgumentException("素材仅支持 PNG、JPEG、WebP、GIF 图片")
            }
            var width: Int? = null
            var height: Int? = null
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (readers.hasNext()) {
                    val reader = readers.next()
                    try {
                        reader.input = input
                        width = reader.getWidth(0)
                        height = reader.getHeight(0)
                        require(width!! > 0 && height!! > 0 && width!!.toLong() * height!! <= 100_000_000L) {
                            "图片像素数量无效或超过工程上限"
                        }
                    } finally {
                        reader.dispose()
                    }
                } else {
                    require(format == "webp" && bytes.size > 20) { "图片已损坏或无法读取" }
                }
            }
            return ImageBinary(bytes, format, width, height)
        }
    }
}
