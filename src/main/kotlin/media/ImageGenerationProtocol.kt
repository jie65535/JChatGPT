package top.jie65535.mirai.media

import kotlinx.serialization.json.*
import top.jie65535.mirai.llm.ResolvedModelDefinition

internal data class GeneratedImage(val base64: String? = null, val url: String? = null)

internal object ImageGenerationProtocol {
    const val MAX_REFERENCES = 16

    fun payload(
        model: ResolvedModelDefinition,
        prompt: String,
        images: List<ImageBinary>,
        options: JsonObject,
        watermark: Boolean,
    ): JsonObject {
        require(prompt.isNotBlank() && prompt.length <= 32_000) { "绘图提示词须为 1~32000 字符" }
        require(images.size <= MAX_REFERENCES) { "参考图最多 $MAX_REFERENCES 张" }
        val extra = model.extraBody.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject }
        return if (model.providerType == "dashscope") {
            require(images.size <= 3) { "千问原生接口最多支持 3 张参考图" }
            buildJsonObject {
                extra?.forEach { (key, value) -> put(key, value) }
                put("model", model.model)
                putJsonObject("input") {
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                images.forEach { image -> addJsonObject { put("image", image.dataUrl()) } }
                                addJsonObject { put("text", prompt) }
                            }
                        }
                    }
                }
                putJsonObject("parameters") {
                    (extra?.get("parameters") as? JsonObject)?.forEach { (key, value) -> put(key, value) }
                    put("n", 1); put("prompt_extend", true); put("watermark", watermark)
                }
            }
        } else {
            buildJsonObject {
                put("size", "auto"); put("quality", "auto"); put("background", "auto"); put("output_format", "png")
                extra?.forEach { (key, value) -> put(key, value) }
                options.forEach { (key, value) -> put(key, value) }
                put("model", model.model); put("prompt", prompt); put("n", 1)
            }.also { body ->
                val size = body["size"]?.jsonPrimitive?.content.orEmpty()
                require(size == "auto" || Regex("[1-9][0-9]{0,4}x[1-9][0-9]{0,4}").matches(size)) {
                    "size 应为 auto 或 WIDTHxHEIGHT"
                }
                require(body["background"]?.jsonPrimitive?.content != "transparent" ||
                    body["output_format"]?.jsonPrimitive?.content != "jpeg") { "透明背景不能使用 JPEG" }
                require(body["output_format"]?.jsonPrimitive?.content in setOf("png", "jpeg", "webp")) {
                    "output_format 应为 png、jpeg 或 webp"
                }
            }
        }
    }

    fun endpoint(model: ResolvedModelDefinition, editing: Boolean): String =
        if (model.providerType == "dashscope") model.api
        else model.api.trimEnd('/') + if (editing) "/images/edits" else "/images/generations"

    fun multipart(payload: JsonObject, images: List<ImageBinary>): ImageEditMultipart = ImageEditMultipart(payload, images)

    fun parseResponse(response: JsonObject, dashScope: Boolean): List<GeneratedImage> {
        val images = if (dashScope) {
            response.getValue("output").jsonObject.getValue("choices").jsonArray.flatMap { choice ->
                choice.jsonObject.getValue("message").jsonObject.getValue("content").jsonArray.mapNotNull {
                    it.jsonObject["image"]?.jsonPrimitive?.content?.let { url -> GeneratedImage(url = url) }
                }
            }
        } else response.getValue("data").jsonArray.map { item ->
            val entry = item.jsonObject
            GeneratedImage(entry["b64_json"]?.jsonPrimitive?.contentOrNull, entry["url"]?.jsonPrimitive?.contentOrNull)
        }
        require(images.isNotEmpty() && images.size <= 10 && images.all { !it.base64.isNullOrBlank() || !it.url.isNullOrBlank() }) {
            "绘图接口没有返回可用图片"
        }
        return images
    }
}
