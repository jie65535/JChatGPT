package top.jie65535.mirai.tools

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisualImageResolverTest {
    private val resolver = VisualImageResolver()

    @Test
    fun `png is preserved as png data url`() {
        val source = createImage("png")

        val result = resolver.prepare(source, "application/octet-stream")
        val payload = result.images.single()

        assertEquals("image/png", payload.mimeType)
        assertFalse(result.transcoded)
        assertTrue(payload.dataUrl.startsWith("data:image/png;base64,"))
    }

    @Test
    fun `gif is converted to supported png`() {
        val source = createImage("gif")

        val result = resolver.prepare(source, "image/gif")
        val payload = result.images.single()

        assertEquals("image/png", payload.mimeType)
        assertTrue(result.transcoded)
        assertTrue(payload.dataUrl.startsWith("data:image/png;base64,"))
    }

    @Test
    fun `unknown content is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            resolver.prepare("not an image".toByteArray(), "application/octet-stream")
        }
    }

    @Test
    fun `small image is enlarged above model minimum`() {
        val source = createImage("png", width = 8, height = 6)

        val result = resolver.prepare(source, "image/png")
        val normalized = decodeDataUrl(result.images.single().dataUrl)

        assertTrue(result.transcoded)
        assertTrue(normalized.width >= 32)
        assertTrue(normalized.height >= 32)
    }

    @Test
    fun `extreme aspect ratio is padded without stretching content`() {
        val source = createImage("png", width = 1000, height = 2)

        val result = resolver.prepare(source, "image/png")
        val normalized = decodeDataUrl(result.images.single().dataUrl)
        val ratio = maxOf(normalized.width, normalized.height).toDouble() /
                minOf(normalized.width, normalized.height).toDouble()

        assertTrue(result.transcoded)
        assertTrue(normalized.width > 10 && normalized.height > 10)
        assertTrue(ratio <= 200.0)
    }

    @Test
    fun `large image is reduced to normalized bounds`() {
        val source = createImage("png", width = 4100, height = 1200)

        val result = resolver.prepare(source, "image/png")
        val normalized = decodeDataUrl(result.images.single().dataUrl)

        assertTrue(result.transcoded)
        assertTrue(maxOf(normalized.width, normalized.height) <= 4096)
        assertTrue(normalized.width.toLong() * normalized.height <= 16_000_000L)
    }

    @Test
    fun `long screenshot is split into overlapping ordered parts`() {
        val source = createImage("png", width = 400, height = 2400)

        val result = resolver.prepare(source, "image/png")

        assertTrue(result.transcoded)
        assertTrue(result.images.size > 1)
        assertTrue(result.images.size <= 16)
        assertTrue(result.orderHint?.contains("从上到下") == true)
        result.images.forEach { payload ->
            val tile = decodeDataUrl(payload.dataUrl)
            assertTrue(tile.width > 10 && tile.height > 10)
            assertTrue(maxOf(tile.width, tile.height) <= 4096)
        }
    }

    @Test
    fun `vertical chat screenshot is split from three to one`() {
        val source = createImage("png", width = 800, height = 2500)

        val result = resolver.prepare(source, "image/png")

        assertTrue(result.images.size > 1)
        assertTrue(result.orderHint?.contains("从上到下") == true)
    }

    @Test
    fun `normal phone screenshot is not split`() {
        val source = createImage("png", width = 1080, height = 2400)

        val result = resolver.prepare(source, "image/png")

        assertEquals(1, result.images.size)
        assertEquals(null, result.orderHint)
    }

    @Test
    fun `horizontal panorama below six to one is not split`() {
        val source = createImage("png", width = 2500, height = 500)

        val result = resolver.prepare(source, "image/png")

        assertEquals(1, result.images.size)
        assertEquals(null, result.orderHint)
    }

    @Test
    fun `private and special addresses are rejected`() {
        assertFalse(VisualImageResolver.isPublicAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(VisualImageResolver.isPublicAddress(InetAddress.getByName("192.168.1.10")))
        assertFalse(VisualImageResolver.isPublicAddress(InetAddress.getByName("100.64.0.1")))
        assertFalse(VisualImageResolver.isPublicAddress(InetAddress.getByName("::1")))
        assertFalse(VisualImageResolver.isPublicAddress(InetAddress.getByName("fd00::1")))
    }

    @Test
    fun `public addresses are accepted`() {
        assertTrue(VisualImageResolver.isPublicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(VisualImageResolver.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    private fun createImage(format: String, width: Int = 64, height: Int = 48): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color.BLUE
            graphics.fillRect(0, 0, maxOf(1, width / 2), maxOf(1, height / 2))
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, format, output))
            output.toByteArray()
        }
    }

    private fun decodeDataUrl(dataUrl: String): BufferedImage {
        val encoded = dataUrl.substringAfter(',')
        val bytes = Base64.getDecoder().decode(encoded)
        return checkNotNull(ImageIO.read(bytes.inputStream()))
    }
}
