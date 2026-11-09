package top.jie65535.mirai.data

import top.jie65535.mirai.media.ImageBinary
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class ImageAssetStoreTest {
    @Test
    fun collectionAndMetadataPersistAndExactDuplicatesReuseTheOriginalFile() = withDirectory { directory ->
        val store = ImageAssetStore(directory)
        val image = image()
        val generated = store.save(image, "生成结果", "generated", model = "test-model", prompt = "测试")
        assertContains(File(directory, "metadata/${generated.id}.json").readText(), "\"createdAt\"")
        assertTrue(store.list().isEmpty())
        assertContentEquals(image.bytes, store.file(generated.id).readBytes())
        val collected = store.update(generated.id, "大肥鱼", "白色肚皮", listOf(" 角色 ", "角色", ""), true)
        assertEquals(listOf("角色"), collected.tags)
        assertEquals(generated.id, store.save(image, "重复生成", "generated").id)
        val reopened = ImageAssetStore(directory)
        assertEquals(collected, reopened.list().single())
        assertEquals("test-model", reopened.get(generated.id).model)
        assertEquals(8, reopened.get(generated.id).width)
        assertEquals("大肥鱼", reopened.list("角色").single().name)
        reopened.update(generated.id, collected = false)
        assertTrue(ImageAssetStore(directory).list().isEmpty())
        assertTrue(reopened.file(generated.id).exists())
    }

    @Test
    fun importsExistingDirectoryWithoutModifyingOriginalsOrRecollectingRemovedImages() = withDirectory { directory ->
        val source = File(directory, "source").apply { mkdirs() }
        val original = File(source, "大肥鱼.gif").apply { writeBytes(image("gif").bytes) }
        File(source, "readme.txt").writeText("保留")
        val bytes = original.readBytes()
        val store = ImageAssetStore(File(directory, "library"))
        assertEquals(1, store.importDirectory(source))
        val asset = store.list().single()
        assertEquals("gif", asset.format)
        assertContentEquals(bytes, store.file(asset.id).readBytes())
        store.update(asset.id, collected = false)
        assertEquals(0, ImageAssetStore(File(directory, "library")).importDirectory(source))
        assertContentEquals(bytes, original.readBytes())
        assertEquals("保留", File(source, "readme.txt").readText())
    }

    @Test
    fun corruptedMetadataIsPreservedAndCannotBeOverwrittenByImport() = withDirectory { directory ->
        val store = ImageAssetStore(directory)
        val asset = store.save(image(), "原记录", "local", true)
        val metadata = File(directory, "metadata/${asset.id}.json")
        metadata.writeText("broken-json")
        val errors = mutableListOf<String>()
        val reopened = ImageAssetStore(directory) { message, _ -> errors += message }
        assertEquals(1, errors.size)
        assertFailsWith<IllegalArgumentException> { reopened.save(image(), "覆盖", "local", true) }
        assertEquals("broken-json", metadata.readText())
        assertTrue(store.file(asset.id).isFile)
    }

    @Test
    fun rejectsInvalidFilesAndMetadataAndEscapesMarkdownLabels() = withDirectory { directory ->
        assertFailsWith<IllegalArgumentException> { ImageBinary.inspect("not-image".toByteArray()) }
        val store = ImageAssetStore(directory)
        assertFailsWith<IllegalArgumentException> { store.save(image(), "", "chat") }
        val asset = store.save(image(), "坏]名字\n[", "chat")
        assertFalse(asset.markdown.substringBefore("](").drop(2).contains("]"))
        assertFalse(asset.markdown.contains('\n'))
        assertFailsWith<IllegalArgumentException> { store.file("../../private") }
        assertFailsWith<IllegalArgumentException> { store.update(asset.id, tags = List(21) { "tag$it" }) }
    }

    private fun image(format: String = "png"): ImageBinary {
        val output = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB), format, output)
        return ImageBinary.inspect(output.toByteArray())
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("image-assets-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
