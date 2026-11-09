package top.jie65535.mirai.media

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import top.jie65535.mirai.data.ImageAssetStore
import top.jie65535.mirai.llm.ResolvedModelDefinition
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.test.*

class ImageGenerationServiceTest {
    private val image = image(Color.BLUE)

    @Test
    fun generatesFromJsonAndKeepsSavedImageAcrossRestart() = withServer { server, client, directory ->
        val calls = AtomicInteger()
        val body = AtomicReference("")
        server.createContext("/v1/images/generations") { exchange ->
            calls.incrementAndGet()
            assertEquals("Bearer test-token", exchange.requestHeaders.getFirst("Authorization"))
            body.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            exchange.respond(200, """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(image.bytes)}"}]}""")
        }
        val store = ImageAssetStore(File(directory, "library"))
        val model = model(server).copy(extraBody = """{"quality":"high","n":9,"model":"wrong"}""")
        val result = generate(service(client, store), model)
        assertNull(result.error)
        assertEquals(1, result.generatedImages)
        assertEquals(12, result.assets.single().width)
        assertTrue(store.list().isEmpty())
        assertContentEquals(image.bytes, store.file(result.assets.single().id).readBytes())
        val submitted = Json.parseToJsonElement(body.get()).jsonObject
        assertEquals("high", submitted["quality"]?.jsonPrimitive?.content)
        assertEquals(1, submitted["n"]?.jsonPrimitive?.int)
        assertEquals("test-image", submitted["model"]?.jsonPrimitive?.content)
        assertFalse(submitted.containsKey("response_format"))
        val reopened = ImageAssetStore(File(directory, "library"))
        assertEquals(result.assets.single(), reopened.get(result.assets.single().id))
        assertContentEquals(image.bytes, reopened.file(result.assets.single().id).readBytes())
        assertEquals(1, calls.get())
    }

    @Test
    fun sendsOrderedReferenceFilesUsingMultipartEdits() = withServer { server, client, directory ->
        val captured = AtomicReference("")
        val contentType = AtomicReference("")
        server.createContext("/v1/images/edits") { exchange ->
            captured.set(exchange.requestBody.readBytes().toString(Charsets.ISO_8859_1))
            contentType.set(exchange.requestHeaders.getFirst("Content-Type"))
            exchange.respond(200, """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(image.bytes)}"}]}""")
        }
        val service = service(client, ImageAssetStore(File(directory, "library")))
        val result = service.generate(model(server), "保留图1角色，采用图2动作", listOf(image, image(Color.RED)),
            listOf("asset:first", "chat:2"), JsonObject(emptyMap()), false, 5000)
        assertNull(result.error)
        assertTrue(contentType.get().startsWith("multipart/form-data;"))
        assertTrue(captured.get().contains("name=\"image[]\""))
        assertTrue(captured.get().indexOf("input-0.png") < captured.get().indexOf("input-1.png"))
        assertEquals(listOf("asset:first", "chat:2"), result.assets.single().references)
    }

    @Test
    fun uploadsSingleReferenceWithUtf8FieldsAndPortableMultipartHeaders() = withServer { server, client, directory ->
        val captured = AtomicReference(ByteArray(0))
        val contentType = AtomicReference("")
        val declaredLength = AtomicReference("")
        val accept = AtomicReference("")
        server.createContext("/v1/images/edits") { exchange ->
            captured.set(exchange.requestBody.readBytes())
            contentType.set(exchange.requestHeaders.getFirst("Content-Type"))
            declaredLength.set(exchange.requestHeaders.getFirst("Content-Length"))
            accept.set(exchange.requestHeaders.getFirst("Accept"))
            exchange.respond(200, """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(image.bytes)}"}]}""")
        }
        val prompt = "保留角色。\r\n标题改为\"星际茶馆\"，英文 SPACE TEA，文字含 & 和 =。"
        val service = service(client, ImageAssetStore(File(directory, "library")))
        val result = service.generate(model(server), prompt, listOf(image), listOf("chat:2"),
            buildJsonObject { put("size", "1024x1024") }, false, 5000)
        assertNull(result.error)
        assertEquals("application/json", accept.get())
        assertEquals(captured.get().size.toString(), declaredLength.get())
        val boundary = requireNotNull(ContentType.parse(contentType.get()).parameter("boundary"))
        val wire = captured.get().toString(Charsets.ISO_8859_1)
        assertTrue(wire.startsWith("--$boundary\r\n"))
        assertTrue(wire.endsWith("--$boundary--\r\n"))
        val parts = wire.split("--$boundary").drop(1).dropLast(1).associate { part ->
            val (headers, body) = part.removePrefix("\r\n").removeSuffix("\r\n").split("\r\n\r\n", limit = 2)
            assertFalse(headers.contains("Content-Length", ignoreCase = true))
            val disposition = ContentDisposition.parse(headers.lineSequence().first().substringAfter(": "))
            val name = requireNotNull(disposition.parameter("name"))
            assertContains(headers, "name=\"$name\"")
            if (name == "image") {
                assertEquals("input-0.png", disposition.parameter("filename"))
                assertContains(headers, "Content-Type: image/png")
            }
            name to body.toByteArray(Charsets.ISO_8859_1)
        }
        assertEquals(setOf("model", "prompt", "n", "size", "quality", "background", "output_format", "image"), parts.keys)
        assertEquals(prompt, parts.getValue("prompt").toString(Charsets.UTF_8))
        assertEquals("auto", parts.getValue("background").toString(Charsets.UTF_8))
        assertContentEquals(image.bytes, parts.getValue("image"))
    }

    @Test
    fun rejectsFileParametersAndHeaderInjectionInExtraFields() {
        listOf("image", "image[]", "mask", "bad\r\nInjected: header").forEach { field ->
            assertFailsWith<IllegalArgumentException> {
                ImageGenerationProtocol.multipart(buildJsonObject { put(field, "invalid") }, listOf(image))
            }
        }
    }

    @Test
    fun returnsDownloadFailureWithoutGeneratingOrDownloadingAgain() = withServer { server, client, directory ->
        val posts = AtomicInteger()
        val downloads = AtomicInteger()
        server.createContext("/v1/images/generations") { exchange ->
            posts.incrementAndGet()
            exchange.respond(200, """{"data":[{"url":"https://example.com/result?signature=private"}]}""")
        }
        val store = ImageAssetStore(File(directory, "library"))
        val service = service(client, store) { url ->
            downloads.incrementAndGet()
            assertEquals("https://example.com/result?signature=private", url)
            throw IllegalStateException("download unavailable")
        }
        val result = generate(service, model(server))
        assertContains(requireNotNull(result.error), "下载或保存失败")
        assertFalse(result.error.contains("signature=private"))
        assertEquals(1, result.generatedImages)
        assertTrue(result.assets.isEmpty())
        assertTrue(store.list(includeTemporary = true).isEmpty())
        assertEquals(1, posts.get())
        assertEquals(1, downloads.get())
    }

    @Test
    fun preservesSavedImagesWhenAnotherResultCannotBeDownloaded() = withServer { server, client, directory ->
        val posts = AtomicInteger()
        server.createContext("/v1/images/generations") { exchange ->
            posts.incrementAndGet()
            exchange.respond(200, """{"data":[{"url":"https://example.com/first"},{"url":"https://example.com/second"}]}""")
        }
        val store = ImageAssetStore(File(directory, "library"))
        val service = service(client, store) { url ->
            if (url.endsWith("/first")) image else throw IllegalStateException("download unavailable")
        }
        val result = generate(service, model(server))
        assertNotNull(result.error)
        assertEquals(2, result.generatedImages)
        assertContentEquals(image.bytes, store.file(result.assets.single().id).readBytes())
        assertEquals(result.assets.single(), ImageAssetStore(File(directory, "library")).get(result.assets.single().id))
        assertEquals(1, posts.get())
    }

    @Test
    fun returnsHttpErrorsAndMalformedResponsesWithoutAutomaticRetries() = withServer { server, client, directory ->
        val posts = AtomicInteger()
        val status = AtomicInteger(503)
        server.createContext("/v1/images/generations") { exchange ->
            posts.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            exchange.respond(status.get(), "invalid upstream response")
        }
        val service = service(client, ImageAssetStore(File(directory, "library")))
        assertContains(requireNotNull(generate(service, model(server)).error), "HTTP 503")
        assertEquals(1, posts.get())
        status.set(400)
        assertContains(requireNotNull(generate(service, model(server)).error), "HTTP 400")
        assertEquals(2, posts.get())
        status.set(200)
        val malformed = generate(service, model(server))
        assertContains(requireNotNull(malformed.error), "无法确认")
        assertEquals(0, malformed.generatedImages)
        assertEquals(3, posts.get())
    }

    @Test
    fun timesOutWithoutRetryingAndDoesNotForwardCredentialsThroughRedirects() = withServer { server, client, directory ->
        val posts = AtomicInteger()
        val forwarded = AtomicInteger()
        server.createContext("/v1/images/generations") { exchange ->
            posts.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Location", "/redirect-target")
            exchange.responseHeaders.add("Connection", "close")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.createContext("/redirect-target") { exchange ->
            forwarded.incrementAndGet()
            exchange.respond(200, "{}")
        }
        val service = service(client, ImageAssetStore(File(directory, "library")))
        assertContains(requireNotNull(generate(service, model(server)).error), "HTTP 307")
        assertEquals(0, forwarded.get())
        server.removeContext("/v1/images/generations")
        server.createContext("/v1/images/generations") { exchange ->
            posts.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            Thread.sleep(1000)
            exchange.close()
        }
        val timedOut = service.generate(model(server), "测试超时", emptyList(), emptyList(), JsonObject(emptyMap()), false, 250)
        assertContains(requireNotNull(timedOut.error), "无法确认")
        assertEquals(2, posts.get())
    }

    @Test
    fun nativeDashScopeStillUsesItsMessageProtocolAndDownloadsTheResult() = withServer { server, client, directory ->
        val captured = AtomicReference("")
        server.createContext("/native") { exchange ->
            captured.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            exchange.respond(200, """{"output":{"choices":[{"message":{"content":[{"image":"https://example.com/image"}]}}]}}""")
        }
        val native = model(server).copy(providerType = "dashscope", api = "http://127.0.0.1:${server.address.port}/native")
        val service = service(client, ImageAssetStore(File(directory, "library")))
        val result = service.generate(native, "测试", listOf(image), listOf("chat:1"), JsonObject(emptyMap()), false, 5000)
        assertNull(result.error)
        val body = Json.parseToJsonElement(captured.get()).jsonObject
        val content = body.getValue("input").jsonObject.getValue("messages").jsonArray.single()
            .jsonObject.getValue("content").jsonArray
        assertTrue(content.first().jsonObject.getValue("image").jsonPrimitive.content.startsWith("data:image/png;base64,"))
        assertEquals("测试", content.last().jsonObject.getValue("text").jsonPrimitive.content)
    }

    private suspend fun generate(service: ImageGenerationService, model: ResolvedModelDefinition) =
        service.generate(model, "测试", emptyList(), emptyList(), JsonObject(emptyMap()), false, 5000)

    private fun service(client: HttpClient, store: ImageAssetStore, download: suspend (String) -> ImageBinary = { image }) =
        ImageGenerationService(client, store, download)

    private fun model(server: HttpServer) = ResolvedModelDefinition("image-main", "test", "openai",
        "http://127.0.0.1:${server.address.port}/v1/", "test-token", "test-image", "")

    private fun HttpExchange.respond(status: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }

    private fun withServer(block: suspend (HttpServer, HttpClient, File) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("image-generation-").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val client = HttpClient(OkHttp) {
            followRedirects = false
            engine { config { retryOnConnectionFailure(false) } }
            install(HttpTimeout)
        }
        server.start()
        try { block(server, client, directory) } finally {
            client.close()
            server.stop(0)
            directory.deleteRecursively()
        }
    }

    private fun image(color: Color): ImageBinary {
        val bitmap = BufferedImage(12, 9, BufferedImage.TYPE_INT_ARGB)
        bitmap.setRGB(0, 0, color.rgb)
        val output = ByteArrayOutputStream()
        ImageIO.write(bitmap, "png", output)
        return ImageBinary.inspect(output.toByteArray())
    }
}
