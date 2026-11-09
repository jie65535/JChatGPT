package top.jie65535.mirai.media

import kotlinx.serialization.json.*
import top.jie65535.mirai.data.ImageAsset
import kotlin.test.*

class ImageLibraryPageTest {
    @Test
    fun longMetadataProducesCompleteJsonPagesWithoutSkippingAssets() {
        val assets = (1..35).map { index -> ImageAsset(
            id = "img_" + index.toString().padStart(64, '0'), format = "png", name = "图片$index",
            description = "长描述\"".repeat(200), tags = List(20) { "标签$it" }, collected = true, source = "local") }
        var offset = 0
        val seen = mutableListOf<String>()
        do {
            val page = ImageLibraryPage.render(assets, offset, 30, 3000)
            assertTrue(page.toString().length <= 3000)
            val parsed = Json.parseToJsonElement(page.toString()).jsonObject
            val images = parsed.getValue("images").jsonArray
            assertTrue(images.isNotEmpty())
            seen += images.map { it.jsonObject.getValue("image").jsonPrimitive.content }
            if (!parsed.getValue("has_more").jsonPrimitive.boolean) break
            val next = parsed.getValue("next_offset").jsonPrimitive.int
            assertTrue(next > offset)
            offset = next
        } while (true)
        assertEquals(assets.map { it.reference }, seen)
        assertTrue(ImageLibraryPage.render(assets, 0, 30, 512).toString().length <= 512)
        assertFailsWith<IllegalArgumentException> { ImageLibraryPage.render(assets, -1, 20, 3000) }
        assertTrue(ImageLibraryPage.render(assets, 100, 30, 3000).getValue("images").jsonArray.isEmpty())
    }
}
