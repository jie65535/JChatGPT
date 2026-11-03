package top.jie65535.mirai.tools

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepSeekToolsClientTest {
    private data class ReceivedRequest(val method: String, val path: String, val auth: String?, val contentType: String?, val body: String)

    private suspend fun withServer(
        responses: List<Pair<Int, String>>,
        requestTimeoutMillis: Long = 5_000,
        shouldRespond: (Int) -> Boolean = { true },
        block: suspend (HttpClient, DeepSeekToolsClient.Endpoint, List<ReceivedRequest>) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val requests = Collections.synchronizedList(mutableListOf<ReceivedRequest>())
        val nextResponse = AtomicInteger()
        server.createContext("/v1/tools/") { exchange ->
            requests += ReceivedRequest(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestHeaders.getFirst("Content-Type"),
                String(exchange.requestBody.readBytes(), Charsets.UTF_8),
            )
            val index = nextResponse.getAndIncrement()
            if (!shouldRespond(index)) {
                exchange.close()
                return@createContext
            }
            val (status, response) = responses[index.coerceAtMost(responses.lastIndex)]
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val httpClient = HttpClient(OkHttp) {
            engine { config { retryOnConnectionFailure(false) } }
            install(HttpTimeout) { this.requestTimeoutMillis = requestTimeoutMillis }
        }
        try {
            block(httpClient, DeepSeekToolsClient.Endpoint("http://127.0.0.1:${server.address.port}/v1", "test-key", "tools"), requests)
        } finally {
            httpClient.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `posts official search fields with bearer auth and returns response unchanged`() = runBlocking {
        val responseBody = """{ "results": [{"title":"Docs","url":"https://example.com","summary":"官方摘要","snippet":"摘要","content":"","rerank_score":2.4,"authority":3,"date":null,"site_name":null,"site_icon":null}] }"""
        val args = Json.parseToJsonElement("""{"query":"Kotlin","count":5,"ori_question":"如何使用？","localization":"203.0.113.1","need_content":false}""").jsonObject
        withServer(listOf(200 to responseBody)) { httpClient, endpoint, requests ->
            val response = DeepSeekToolsClient(httpClient).request(endpoint, "search", args)
            assertEquals(responseBody, response.body)
            assertTrue(response.billable)
            val received = requests.single()
            assertEquals("POST", received.method)
            assertEquals("/v1/tools/search", received.path)
            assertEquals("Bearer test-key", received.auth)
            assertTrue(received.contentType.orEmpty().startsWith("application/json"))
            assertEquals(args, Json.parseToJsonElement(received.body))
        }
    }

    @Test
    fun `fetch returns raw Markdown in JSON and counts only nonempty successful results`() = runBlocking {
        for ((operation, responseBody, billable) in listOf(
            Triple("fetch", """{"title":"Docs","content":"# Article\n\nText","extra":null}""", true),
            Triple("fetch", """{"title":"","content":""}""", false),
            Triple("search", """{"results":[]}""", false),
        )) {
            withServer(listOf(200 to responseBody)) { httpClient, endpoint, requests ->
                val request = if (operation == "fetch") buildJsonObject { put("url", "https://example.com") }
                else buildJsonObject { put("query", "Kotlin") }
                val response = DeepSeekToolsClient(httpClient).request(endpoint, operation, request)
                assertEquals(responseBody, response.body)
                assertEquals(billable, response.billable)
                assertEquals("/v1/tools/$operation", requests.single().path)
                assertEquals(request, Json.parseToJsonElement(requests.single().body))
            }
        }
    }

    @Test
    fun `retries transient HTTP errors at most twice before returning final result`() = runBlocking {
        val success = """{"results":[{"summary":"found"}]}"""
        for (status in listOf(408, 429, 500, 504)) {
            withServer(listOf(status to "unavailable", status to "unavailable", 200 to success)) { httpClient, endpoint, requests ->
                val waits = mutableListOf<Long>()
                val client = DeepSeekToolsClient(httpClient) { waits += it }
                val response = client.request(endpoint, "search", buildJsonObject { put("query", "Kotlin") })
                assertEquals(success, response.body)
                assertTrue(response.billable)
                assertEquals(3, requests.size)
                assertEquals(2, waits.size)
                assertTrue(waits.all { it > 0 })
            }
        }
        withServer(listOf(503 to "unavailable")) { httpClient, endpoint, requests ->
            val response = DeepSeekToolsClient(httpClient) {}.request(endpoint, "fetch", buildJsonObject { put("url", "https://example.com") })
            assertFalse(response.billable)
            assertEquals(3, requests.size)
            assertTrue(response.body.contains("HTTP 503"))
        }
    }

    @Test
    fun `client timeout and HTTP failure share retry budget and report progress`() = runBlocking {
        val releaseFirstRequest = CountDownLatch(1)
        val success = """{"results":[{"summary":"found"}]}"""
        try {
            withServer(
                responses = listOf(200 to success, 503 to "unavailable", 200 to success),
                requestTimeoutMillis = 500,
                shouldRespond = { index ->
                    if (index == 0) {
                        releaseFirstRequest.await(5, TimeUnit.SECONDS)
                        false
                    } else {
                        releaseFirstRequest.countDown()
                        true
                    }
                },
            ) { httpClient, endpoint, requests ->
                val logs = mutableListOf<String>()
                val waits = mutableListOf<Long>()
                val client = DeepSeekToolsClient(httpClient, logDiagnostic = logs::add) { waits += it }
                val response = client.request(endpoint, "search", buildJsonObject { put("query", "Kotlin") })
                assertEquals(success, response.body)
                assertTrue(response.billable)
                assertEquals(3, requests.size)
                assertEquals(2, waits.size)
                assertEquals(3, logs.count { it.contains("开始第") })
                assertTrue(logs.any { it.contains("HttpRequestTimeoutException") && it.contains("耗时") })
                assertTrue(logs.any { it.contains("HTTP 503") })
                assertTrue(logs.any { it.contains("HTTP 200") })
                assertTrue(logs.any { it.contains("重试（2/2）") })
                assertTrue(logs.none { it.contains(endpoint.token) || it.contains("Kotlin") })
            }
        } finally {
            releaseFirstRequest.countDown()
        }
    }

    @Test
    fun `repeated client timeouts stop after two retries`() = runBlocking {
        val releaseRequests = CountDownLatch(1)
        try {
            withServer(
                responses = listOf(200 to """{"results":[]}"""),
                requestTimeoutMillis = 500,
                shouldRespond = {
                    releaseRequests.await(5, TimeUnit.SECONDS)
                    false
                },
            ) { httpClient, endpoint, requests ->
                val waits = mutableListOf<Long>()
                val client = DeepSeekToolsClient(httpClient) { waits += it }
                try {
                    assertFailsWith<HttpRequestTimeoutException> {
                        client.request(endpoint, "search", buildJsonObject { put("query", "Kotlin") })
                    }
                    assertEquals(3, requests.size)
                    assertEquals(2, waits.size)
                } finally {
                    releaseRequests.countDown()
                }
            }
        } finally {
            releaseRequests.countDown()
        }
    }

    @Test
    fun `cancelling active request does not trigger network retries`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val releaseRequest = CountDownLatch(1)
        try {
            withServer(
                responses = listOf(200 to """{"results":[]}"""),
                shouldRespond = {
                    started.complete(Unit)
                    releaseRequest.await(5, TimeUnit.SECONDS)
                    false
                },
            ) { httpClient, endpoint, requests ->
                val waits = mutableListOf<Long>()
                val client = DeepSeekToolsClient(httpClient) { waits += it }
                val request = async {
                    client.request(endpoint, "search", buildJsonObject { put("query", "Kotlin") })
                }
                try {
                    started.await()
                    request.cancel()
                    assertFailsWith<CancellationException> { request.await() }
                    assertEquals(1, requests.size)
                    assertTrue(waits.isEmpty())
                } finally {
                    request.cancel()
                    releaseRequest.countDown()
                }
            }
        } finally {
            releaseRequest.countDown()
        }
    }

    @Test
    fun `does not retry permanent HTTP errors and redacts credentials in provider errors`() = runBlocking {
        for (status in listOf(400, 401, 402, 403, 404, 413)) {
            val body = """{"error":{"message":"request rejected","type":"invalid_request_error","param":null,"code":"invalid_request_error"}}"""
            withServer(listOf(status to body)) { httpClient, endpoint, requests ->
                val response = DeepSeekToolsClient(httpClient) { error("must not retry") }
                    .request(endpoint, "fetch", buildJsonObject { put("url", "https://example.com") })
                assertEquals(body, response.body)
                assertFalse(response.billable)
                assertEquals(1, requests.size)
            }
        }
        withServer(listOf(401 to """{"error":{"message":"test-key is invalid"}}""")) { httpClient, endpoint, _ ->
            val response = DeepSeekToolsClient(httpClient).request(endpoint, "search", buildJsonObject { put("query", "Kotlin") })
            assertFalse(response.body.contains("test-key"))
            assertTrue(response.body.contains("[REDACTED]"))
            Json.parseToJsonElement(response.body)
        }
    }

    @Test
    fun `malformed successful responses return JSON errors without retry or billing`() = runBlocking {
        for (body in listOf("not JSON", "[]", "{}", """{"results":null}""")) {
            withServer(listOf(200 to body)) { httpClient, endpoint, requests ->
                val response = DeepSeekToolsClient(httpClient).request(endpoint, "search", buildJsonObject { put("query", "Kotlin") })
                assertFalse(response.billable)
                assertEquals("invalid_response", Json.parseToJsonElement(response.body).jsonObject
                    .getValue("error").jsonObject.getValue("type").jsonPrimitive.content)
                assertEquals(1, requests.size)
            }
        }
    }

    @Test
    fun `cancellation during backoff stops retrying`() = runBlocking {
        withServer(listOf(429 to "rate limited")) { httpClient, endpoint, requests ->
            val client = DeepSeekToolsClient(httpClient) { throw CancellationException("cancelled") }
            assertFailsWith<CancellationException> {
                client.request(endpoint, "search", buildJsonObject { put("query", "Kotlin") })
            }
            assertEquals(1, requests.size)
        }
    }
}
