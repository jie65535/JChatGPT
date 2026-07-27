package top.jie65535.mirai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageIndexTest {
    @Test
    fun `assigns short sequential indices`() {
        val index = ImageIndex()

        assertEquals(1, index.add("image-a", "https://example.com/a"))
        assertEquals(2, index.add("image-b", "https://example.com/b"))
        assertEquals("https://example.com/a", index.getUrl(1))
        assertEquals("https://example.com/b", index.getUrl(2))
    }

    @Test
    fun `reuses index for repeated image id`() {
        val index = ImageIndex()

        assertEquals(1, index.add("image-a", "https://example.com/a-old"))
        assertEquals(1, index.add("image-a", "https://example.com/a-new"))
        assertEquals(2, index.add("image-b", "https://example.com/b"))
        assertEquals("https://example.com/a-new", index.getUrl(1))
    }

    @Test
    fun `returns null for unknown index`() {
        assertNull(ImageIndex().getUrl(1))
    }
}
