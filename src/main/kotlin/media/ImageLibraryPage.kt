package top.jie65535.mirai.media

import kotlinx.serialization.json.*
import top.jie65535.mirai.data.ImageAsset

/** 按实际工具输出预算分页，避免元数据较长时在 JSON 中间截断。 */
internal object ImageLibraryPage {
    fun render(assets: List<ImageAsset>, offset: Int, limit: Int, maxCharacters: Int): JsonObject {
        require(offset >= 0 && limit in 1..30) { "offset 须非负，limit 须为 1~30" }
        require(maxCharacters >= 512) { "素材列举需要将 maxToolOutputLength 配置为至少 512" }
        val selected = mutableListOf<JsonObject>()
        for (asset in assets.drop(offset).take(limit)) {
            selected += formatAsset(asset)
            if (page(assets.size, offset, selected).toString().length > maxCharacters) {
                selected.removeAt(selected.lastIndex)
                if (selected.isEmpty()) selected += buildJsonObject {
                    put("image", asset.reference)
                    put("name", asset.name.take(30))
                    put("markdown", "![图片](${asset.reference})")
                    put("collected", asset.collected)
                }
                break
            }
        }
        return page(assets.size, offset, selected)
    }

    fun formatAsset(asset: ImageAsset): JsonObject = buildJsonObject {
        put("image", asset.reference)
        put("name", asset.name)
        put("description", asset.description)
        putJsonArray("tags") { asset.tags.forEach { add(it) } }
        put("collected", asset.collected)
        put("source", asset.source)
        asset.width?.let { put("width", it) }
        asset.height?.let { put("height", it) }
        put("markdown", asset.markdown)
    }

    private fun page(total: Int, offset: Int, images: List<JsonObject>): JsonObject = buildJsonObject {
        val hasMore = offset.toLong() + images.size < total
        put("total", total)
        put("offset", offset)
        put("has_more", hasMore)
        if (hasMore) put("next_offset", offset + images.size)
        putJsonArray("images") { images.forEach { add(it) } }
    }
}
