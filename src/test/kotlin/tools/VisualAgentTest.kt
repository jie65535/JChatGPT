package top.jie65535.mirai.tools

import kotlinx.coroutines.runBlocking
import top.jie65535.mirai.media.ImageAssets
import top.jie65535.mirai.media.ImageBinary
import top.jie65535.mirai.media.ImageReferences
import top.jie65535.mirai.media.ImageSource
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.*

class VisualAgentTest {
    @Test
    fun preparesInlineAndLibraryImagesEvenWhenRemoteLocalizationIsDisabled() = runBlocking {
        val directory = Files.createTempDirectory("visual-inline-").toFile()
        val resolver = VisualImageResolver()
        try {
            ImageAssets.init(directory) { message, cause -> throw AssertionError(message, cause) }
            val output = ByteArrayOutputStream()
            ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", output)
            val image = ImageBinary.inspect(output.toByteArray())
            val asset = ImageAssets.requireStore().save(image, "测试图片", "generated")
            val inline = ImageReferences.parse("chat:6") {
                "base64://" + Base64.getEncoder().encodeToString(image.bytes)
            }
            listOf(inline, ImageSource.Asset(asset.id)).forEach { source ->
                val result = assertNotNull(VisualAgent.prepareImage(source, false, resolver))
                val dataUrl = result.images.single().dataUrl
                assertTrue(dataUrl.startsWith("data:image/png;base64,"))
                assertContentEquals(image.bytes, Base64.getDecoder().decode(dataUrl.substringAfter(',')))
            }
        } finally {
            resolver.close()
            ImageAssets.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun keepsRemoteImagesAsUrlsWhenLocalizationIsDisabled() = runBlocking {
        val resolver = VisualImageResolver()
        try {
            val source = ImageReferences.parse("chat:1") { "https://example.com/image.png" }
            assertNull(VisualAgent.prepareImage(source, false, resolver))
        } finally {
            resolver.close()
        }
    }
}
