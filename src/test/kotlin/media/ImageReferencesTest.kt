package top.jie65535.mirai.media

import kotlinx.serialization.json.*
import kotlin.test.*

class ImageReferencesTest {
    private val asset = "asset:img_" + "a".repeat(64)

    @Test
    fun preservesOrderWhenMixingChatAndLibraryReferences() {
        val args = buildJsonObject {
            putJsonArray("images") { add(asset); add("chat:2"); add("chat:1") }
        }
        val sources = ImageReferences.fromArguments(args, 3) { "https://example.com/$it" }
        assertEquals(listOf(asset, "chat:2", "chat:1"), sources.map { it.reference })
        assertEquals("https://example.com/2", (sources[1] as ImageSource.Chat).url)
    }

    @Test
    fun supportsLegacyIndicesAndRejectsAmbiguityMissingImagesAndInvalidReferences() {
        val legacy = buildJsonObject { putJsonArray("image_indices") { add(3) } }
        assertEquals("chat:3", ImageReferences.fromArguments(legacy, 3) { "https://example.com/image" }.single().reference)
        val mixed = buildJsonObject {
            putJsonArray("image_indices") { add(3) }; putJsonArray("images") { add(asset) }
        }
        assertFailsWith<IllegalArgumentException> { ImageReferences.fromArguments(mixed, 3) { null } }
        listOf("chat:0", "chat:-1", "chat:9999999999999", "asset:../../secret", "https://example.com/x",
            "base64://aW1hZ2U=").forEach {
            assertFailsWith<IllegalArgumentException> { ImageReferences.parse(it) { null } }
        }
        assertFailsWith<IllegalArgumentException> { ImageReferences.parse("chat:1") { null } }
        assertFailsWith<IllegalArgumentException> { ImageReferences.fromArguments(legacy, 0) { "https://example.com/x" } }
    }

    @Test
    fun rejectsUnreadableChatAddressesBeforeSendingThemToModels() {
        listOf("file:///private/image.png", "{image-id}.png", "https:///missing-host",
            "https://user:private@example.com/image").forEach { url ->
            val cause = assertFailsWith<IllegalArgumentException> { ImageReferences.parse("chat:1") { url } }
            assertContains(requireNotNull(cause.message), "核对当前消息中的图片编号")
            assertFalse(cause.message.orEmpty().contains(url))
        }
        assertEquals("https://example.com/image", (ImageReferences.parse("chat:2") { " https://example.com/image " } as ImageSource.Chat).url)
    }
}
