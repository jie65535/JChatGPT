package top.jie65535.mirai.media

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI

internal sealed interface ImageSource {
    val reference: String
    data class Chat(val index: Int, val url: String) : ImageSource {
        override val reference: String get() = "chat:$index"
        // Overflow 的发送记录可能保留上传时的内嵌原图，而不是 QQ CDN 地址。
        val isInline: Boolean get() = url.startsWith("base64://")
    }
    data class Asset(val id: String) : ImageSource {
        override val reference: String get() = "asset:$id"
    }
}

internal object ImageReferences {
    private val assetPattern = Regex("asset:(img_[a-f0-9]{64})")
    private val chatPattern = Regex("chat:([1-9][0-9]*)")

    fun parse(reference: String, chatUrl: (Int) -> String?): ImageSource {
        assetPattern.matchEntire(reference)?.let { return ImageSource.Asset(it.groupValues[1]) }
        val index = chatPattern.matchEntire(reference)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IllegalArgumentException("图片引用应为 chat:编号 或素材库返回的 asset:编号")
        val url = chatUrl(index)?.trim() ?: throw IllegalArgumentException("聊天图片 $index 不存在或已失效")
        val source = ImageSource.Chat(index, url)
        if (source.isInline) return source
        val uri = runCatching { URI(url) }.getOrNull()
        require(uri?.scheme?.lowercase() in setOf("http", "https") && !uri?.host.isNullOrBlank() && uri?.userInfo == null) {
            "聊天图片 $index 没有可下载的 HTTP/HTTPS 地址，请核对当前消息中的图片编号，或让用户重新发送图片"
        }
        return source
    }

    fun fromArguments(args: JsonObject, maximum: Int, chatUrl: (Int) -> String?): List<ImageSource> {
        val images = args["images"]?.jsonArray.orEmpty()
        val oldIndices = args["image_indices"]?.jsonArray.orEmpty()
        require(images.isEmpty() || oldIndices.isEmpty()) { "images 和 image_indices 请选择一种传入" }
        val references = if (images.isNotEmpty()) images.map {
            require(it.jsonPrimitive.isString) { "images 中每项必须是图片引用字符串" }
            it.jsonPrimitive.content
        } else oldIndices.map { "chat:${it.jsonPrimitive.int}" }
        require(references.size <= maximum) { "单次最多支持 $maximum 张参考图" }
        return references.map { parse(it, chatUrl) }
    }

    fun schema(builder: JsonObjectBuilder, maximum: Int) = with(builder) {
        putJsonObject("images") {
            put("type", "array")
            put("maxItems", maximum)
            putJsonObject("items") { put("type", "string") }
            put("description", "按参考顺序传入图片引用；聊天[图片n]/[表情包n]用 chat:n，" +
                "使用正文实际标注的编号，不默认从1开始；素材用 imageLibrary 返回的 asset:编号。可混用，必要时先识图确认各图用途。")
        }
        putJsonObject("image_indices") {
            put("type", "array")
            put("maxItems", maximum)
            putJsonObject("items") { put("type", "integer"); put("minimum", 1) }
            put("description", "兼容旧调用：聊天图片编号数组，与 images 二选一。")
        }
    }
}
