package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import net.mamoe.mirai.event.events.MessageEvent
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.config.PluginConfig
import top.jie65535.mirai.media.ImageAssets
import top.jie65535.mirai.media.ImageReferences
import top.jie65535.mirai.media.ImageSource
import top.jie65535.mirai.media.ImageLibraryPage

class ImageLibraryAgent : BaseAgent(
    Tool.function(
        name = "imageLibrary",
        description = "管理所有群和私聊共用的图片素材库。可按需分页列举、收藏聊天图片或生成结果、" +
            "修改名称描述标签、取消收藏。列举不需要关键词；查看画面使用 imageRecognition，" +
            "修改画面使用 imageAgent，发送直接使用返回的 Markdown。是否收藏或复用由当前对话决定。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") { listOf("list", "collect", "update", "uncollect").forEach { add(it) } }
                }
                putJsonObject("image") { put("type", "string"); put("description", "collect 必填：chat:n 或 asset:编号；update/uncollect 必须用 asset:编号") }
                putJsonObject("name") { put("type", "string"); put("maxLength", 100) }
                putJsonObject("description") { put("type", "string"); put("maxLength", 1000) }
                putJsonObject("tags") {
                    put("type", "array"); put("maxItems", 20)
                    putJsonObject("items") { put("type", "string"); put("maxLength", 40) }
                }
                putJsonObject("offset") { put("type", "integer"); put("minimum", 0); put("description", "翻页时使用上次返回的 next_offset") }
                putJsonObject("limit") { put("type", "integer"); put("minimum", 1); put("maximum", 30) }
                putJsonObject("query") { put("type", "string"); put("description", "list 可选：按名称、描述或标签包含文字筛选") }
                putJsonObject("include_temporary") { put("type", "boolean"); put("description", "list 默认只列收藏；设 true 可同时列出未收藏的生成结果及已取消收藏的素材") }
            }
            putJsonArray("required") { add("action") }
        },
    )
) {
    override val isEnabled: Boolean get() = ImageAssets.store != null

    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        requireNotNull(args)
        val store = ImageAssets.requireStore()
        fun text(key: String): String? = args[key]?.jsonPrimitive?.content
        val action = text("action")
        if (action == "list") return withContext(Dispatchers.IO) {
            val offset = args["offset"]?.jsonPrimitive?.int ?: 0
            val limit = args["limit"]?.jsonPrimitive?.int ?: 20
            val all = store.list(text("query").orEmpty(), args["include_temporary"]?.jsonPrimitive?.boolean ?: false)
            ImageLibraryPage.render(all, offset, limit, PluginConfig.maxToolOutputLength).toString()
        }
        require(action in setOf("collect", "update", "uncollect")) { "不支持的素材操作" }
        val source = ImageReferences.parse(requireNotNull(text("image")) { "此操作需要 image" }) {
            JChatGPT.lookupImageUrl(event.subject.id, it)
        }
        val tags = args["tags"]?.jsonArray?.map { it.jsonPrimitive.content }
        val asset = if (action == "collect" && source is ImageSource.Chat) {
            val image = ImageAssets.read(source)
            withContext(Dispatchers.IO) {
                val saved = store.save(image, text("name") ?: "聊天图片", "chat")
                store.update(saved.id, text("name"), text("description"), tags, true)
            }
        } else {
            require(source is ImageSource.Asset) { "修改或取消收藏需要素材库编号" }
            withContext(Dispatchers.IO) {
                store.update(source.id, text("name"), text("description"), tags,
                    when (action) { "collect" -> true; "uncollect" -> false; else -> null })
            }
        }
        return ImageLibraryPage.formatAsset(asset).toString()
    }
}
