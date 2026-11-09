package top.jie65535.mirai.media

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import top.jie65535.mirai.data.ImageAssetStore
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import kotlin.test.*

class ImageAssetsTest {
    @Test
    fun readsBotSentInlineImageByItsChatIndexAndKeepsOriginalWhenCollected() = runBlocking {
        val output = ByteArrayOutputStream()
        val bitmap = BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB)
        bitmap.setRGB(0, 0, 0x804080c0.toInt())
        ImageIO.write(bitmap, "png", output)
        val bytes = output.toByteArray()
        val inline = "base64://" + Base64.getEncoder().encodeToString(bytes)
        val index = ImageIndex()
        repeat(5) { index.add("other-$it", "https://example.com/$it") }
        assertEquals(6, index.add(inline, inline))
        val args = buildJsonObject { putJsonArray("images") { add("chat:6"); add("chat:1") } }
        val sources = ImageReferences.fromArguments(args, 2, index::getUrl)
        assertEquals(listOf("chat:6", "chat:1"), sources.map { it.reference })
        val source = assertIs<ImageSource.Chat>(sources.first())
        assertTrue(source.isInline)
        val image = ImageAssets.read(source)
        assertContentEquals(bytes, image.bytes)
        assertContentEquals(bytes, image.forEditing().bytes)
        assertEquals(8, image.width)
        assertEquals(6, image.height)
        assertEquals(bitmap.getRGB(0, 0), ImageIO.read(ByteArrayInputStream(image.bytes)).getRGB(0, 0))

        val directory = Files.createTempDirectory("inline-image-library-").toFile()
        try {
            val store = ImageAssetStore(directory)
            val saved = store.save(image, "机器人发送的图", "chat", collected = true)
            val reopened = ImageAssetStore(directory)
            assertEquals(saved, reopened.list().single())
            assertContentEquals(bytes, reopened.file(saved.id).readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun keepsAllInlineGifFramesAndOnlyConvertsTheEditingCopy() = runBlocking {
        val output = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        try {
            ImageIO.createImageOutputStream(output).use { stream ->
                writer.output = stream
                writer.prepareWriteSequence(null)
                listOf(Color.BLUE, Color.RED).forEach { color ->
                    val frame = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
                    frame.createGraphics().let { graphics ->
                        try {
                            graphics.color = color
                            graphics.fillRect(0, 0, 32, 32)
                        } finally {
                            graphics.dispose()
                        }
                    }
                    writer.writeToSequence(IIOImage(frame, null, null), null)
                }
                writer.endWriteSequence()
            }
        } finally {
            writer.dispose()
        }
        val bytes = output.toByteArray()
        val source = ImageReferences.parse("chat:1") { "base64://" + Base64.getEncoder().encodeToString(bytes) }
        val image = ImageAssets.read(source)
        assertEquals("gif", image.format)
        assertContentEquals(bytes, image.bytes)
        assertEquals("png", image.forEditing().format)
        assertContentEquals(bytes, image.bytes)
        ImageIO.createImageInputStream(ByteArrayInputStream(image.bytes)).use { stream ->
            val reader = ImageIO.getImageReaders(stream).next()
            try {
                reader.input = stream
                assertEquals(2, reader.getNumImages(true))
            } finally {
                reader.dispose()
            }
        }
    }

    @Test
    fun rejectsEmptyInvalidAndNonImageInlinePayloadsWithoutExposingTheirContents() = runBlocking {
        listOf("", "%%%private-payload", "private", Base64.getEncoder().encodeToString("not-an-image".toByteArray()))
            .forEach { encoded ->
                val inline = "base64://$encoded"
                val source = ImageReferences.parse("chat:1") { inline }
                val cause = assertFailsWith<IllegalArgumentException> { ImageAssets.read(source) }
                assertFalse(cause.message.orEmpty().contains(inline))
            }
    }

    @Test
    fun rejectsOversizedInlinePayloadBeforeDecoding() = runBlocking {
        val encodedLength = (ImageBinary.MAX_BYTES + 2) / 3 * 4 + 1
        val source = ImageSource.Chat(1, "base64://" + "A".repeat(encodedLength))
        val cause = assertFailsWith<IllegalArgumentException> { ImageAssets.read(source) }
        assertContains(cause.message.orEmpty(), "内嵌图片为空或超过 50 MiB")
    }
}
