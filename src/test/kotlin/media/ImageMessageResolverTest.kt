package top.jie65535.mirai.media

import top.jie65535.mirai.data.ImageAssetStore
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class ImageMessageResolverTest {
    @Test
    fun resolvesLibraryReferencesAndRestrictsLegacyFilesToConfiguredDirectory() {
        val directory = Files.createTempDirectory("image-message-").toFile()
        try {
            val output = ByteArrayOutputStream()
            ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "png", output)
            val store = ImageAssetStore(File(directory, "library"))
            val asset = store.save(ImageBinary.inspect(output.toByteArray()), "表情", "generated")
            assertEquals(store.file(asset.id), ImageMessageResolver.localFile(asset.reference, store, ""))
            val legacy = File(directory, "memes").apply { mkdirs() }
            val file = File(legacy, "fish.png").apply { writeBytes(output.toByteArray()) }
            assertEquals(file.canonicalFile, ImageMessageResolver.localFile(file.absolutePath, store, legacy.path))
            assertFailsWith<IllegalArgumentException> {
                ImageMessageResolver.localFile(store.file(asset.id).absolutePath, store, legacy.path)
            }
            assertFailsWith<IllegalArgumentException> { ImageMessageResolver.localFile("file:///secret.png", store, legacy.path) }
            assertFailsWith<IllegalArgumentException> { ImageMessageResolver.localFile("asset:../secret", store, legacy.path) }
            assertNull(ImageMessageResolver.localFile("https://example.com/image.png?key=test", store, legacy.path))
        } finally { directory.deleteRecursively() }
    }
}
