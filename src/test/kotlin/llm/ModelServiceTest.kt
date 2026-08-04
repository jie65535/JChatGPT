package top.jie65535.mirai.llm

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.model.ModelId
import com.sun.net.httpserver.HttpServer
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ModelServiceTest {
    private val service = ModelService(
        baseUrl = "http://localhost/",
        token = "test",
        timeout = 1.seconds,
        firstChunkTimeout = 1.seconds,
    )

    @Test
    fun readsStandardCachedPromptTokens() {
        val usage = service.extractCacheUsage(
            """{"usage":{"prompt_tokens":100,"prompt_tokens_details":{"cached_tokens":75}}}"""
        )

        assertEquals(ModelService.CacheUsage(hitTokens = 75, missTokens = 25), usage)
    }

    @Test
    fun readsDeepSeekCacheTokens() {
        val usage = service.extractCacheUsage(
            """{"usage":{"prompt_cache_hit_tokens":80,"prompt_cache_miss_tokens":20}}"""
        )

        assertEquals(ModelService.CacheUsage(hitTokens = 80, missTokens = 20), usage)
    }

    @Test
    fun ignoresResponsesWithoutCacheDetails() {
        assertNull(service.extractCacheUsage("""{"usage":{"prompt_tokens":100}}"""))
    }

    @Test
    fun configuresGlobalAndPerHostConcurrencyTogether() {
        val dispatcher = createRequestDispatcher(128)

        assertEquals(128, dispatcher.maxRequests)
        assertEquals(128, dispatcher.maxRequestsPerHost)
    }

    @Test
    fun forcesHttp11ForAllModelRequests() {
        assertEquals(listOf(Protocol.HTTP_1_1), MODEL_HTTP_PROTOCOLS)
    }

    @Test
    fun clampsConcurrencyToSafetyRange() {
        assertEquals(1, normalizeMaxConcurrentRequests(0))
        assertEquals(MAX_MODEL_CONCURRENT_REQUESTS, normalizeMaxConcurrentRequests(Int.MAX_VALUE))
    }

    @Test
    fun buildsExplicitStreamingRequestBody() {
        val body = service.buildRequestBody(
            request = ChatCompletionRequest(
                model = ModelId("test-model"),
                messages = listOf(ChatMessage.User("hello")),
            ),
            stream = true,
        )

        assertEquals(
            true,
            Json.parseToJsonElement(body).jsonObject.getValue("stream").jsonPrimitive.content.toBoolean(),
        )
    }

    @Test
    fun acceptsStandaloneUsageAndEmptyHeartbeatEvents() {
        assertNull(service.decodeStreamChunk("{}"))

        val chunk = assertNotNull(
            service.decodeStreamChunk(
                """{"usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14}}"""
            )
        )
        assertTrue(chunk.choices.isEmpty())
        assertEquals(10, chunk.usage?.promptTokens)
        assertEquals(4, chunk.usage?.completionTokens)
    }

    @Test
    fun reportsProviderErrorEventsInsteadOfMissingChunkFields() {
        val failure = assertFailsWith<ModelStreamProtocolException> {
            service.decodeStreamChunk(
                """{"error":{"message":"upstream unavailable","type":"gateway_error"}}"""
            )
        }

        assertTrue(failure.message.orEmpty().contains("upstream unavailable"))
    }

    @Test
    fun classifiesPolicyRejectionsAsNonRetryableSafetyFailures() {
        val failure = assertFailsWith<ModelSafetyRejectionException> {
            service.decodeStreamChunk(
                """{"error":{"code":"cyber_policy","message":"This content was flagged for possible cybersecurity risk.","type":"invalid_request_error"}}"""
            )
        }

        assertEquals("cyber_policy", failure.errorCode)
        assertEquals("invalid_request_error", failure.errorType)
    }

    @Test
    fun classifiesSafetyMessagesEvenWhenGatewayLabelsThemAsUpstreamErrors() {
        val failure = assertFailsWith<ModelSafetyRejectionException> {
            service.decodeStreamChunk(
                """{"error":{"message":"This content was flagged for possible biological risk.","type":"upstream_error"}}"""
            )
        }

        assertEquals("upstream_error", failure.errorType)
        assertNull(failure.errorCode)
    }

    @Test
    fun classifiesOtherInvalidRequestsAsNonRetryableRejections() {
        val failure = assertFailsWith<ModelRequestRejectedException> {
            service.decodeStreamChunk(
                """{"error":{"message":"unsupported parameter","type":"invalid_request_error"}}"""
            )
        }

        assertTrue(failure !is ModelSafetyRejectionException)
    }

    @Test
    fun leavesAmbiguousAccountAccessErrorsRetryableForUpstreamPoolSwitching() {
        val failure = assertFailsWith<ModelStreamProtocolException> {
            service.decodeStreamChunk(
                """{"error":{"message":"Account access is temporarily limited","type":"invalid_request_error"}}"""
            )
        }

        assertTrue(failure.message.orEmpty().contains("temporarily limited"))
    }

    @Test
    fun readsMalformedUtf8SseLinesWithReplacement() = runBlocking {
        val bytes = "data: first".toByteArray() +
                byteArrayOf(0xC3.toByte()) +
                "\r\n\r\ndata: 第二行\n".toByteArray()
        val reader = LenientUtf8LineReader(ByteReadChannel(bytes))

        assertEquals("data: first\uFFFD", reader.readLine())
        assertEquals("", reader.readLine())
        assertEquals("data: 第二行", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun drainsPrefetchedLinesAfterUnderlyingChannelCloses() = runBlocking {
        val channel = ByteReadChannel("data: first\ndata: second\n".toByteArray())
        val reader = LenientUtf8LineReader(channel)

        assertEquals("data: first", reader.readLine())
        assertTrue(channel.isClosedForRead)
        assertEquals("data: second", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun drainsCompleteSseResponseDeliveredBeforeFirstRead() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val responseBody = listOf(
            """data: {"id":"chunk-1","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"role":"assistant","content":"hello"},"finish_reason":null}]}""",
            """data: {"id":"chunk-2","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}""",
            """data: {"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}""",
            "data: [DONE]",
            "",
        ).joinToString("\n\n").toByteArray()
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, responseBody.size.toLong())
            exchange.responseBody.use { body -> body.write(responseBody) }
        }
        server.start()

        val localService = ModelService(
            baseUrl = "http://127.0.0.1:${server.address.port}/v1/",
            token = "test",
            timeout = 1.seconds,
            firstChunkTimeout = 1.seconds,
        )
        try {
            val content = StringBuilder()
            localService.chatCompletions(
                ChatCompletionRequest(
                    model = ModelId("test-model"),
                    messages = listOf(ChatMessage.User("hello")),
                )
            ).collect { chunk ->
                chunk.choices.firstOrNull()?.delta?.content?.let(content::append)
            }

            assertEquals("hello world", content.toString())
        } finally {
            localService.httpClient.close()
            server.stop(0)
        }
    }

    @Test
    fun queuesFlowsBeforeStartingWork() = runBlocking {
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val started = AtomicInteger()
        val firstWaveStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = flow {
            val currentActive = active.incrementAndGet()
            maximumActive.accumulateAndGet(currentActive, ::maxOf)
            if (started.incrementAndGet() == 2) firstWaveStarted.complete(Unit)
            try {
                release.await()
                emit(Unit)
            } finally {
                active.decrementAndGet()
            }
        }
        val semaphore = Semaphore(2)

        coroutineScope {
            val collectors = (1..6).map {
                async { source.withConcurrencyLimit(semaphore).collect() }
            }
            withTimeout(1_000) { firstWaveStarted.await() }
            assertEquals(2, active.get())
            release.complete(Unit)
            collectors.awaitAll()
        }

        assertEquals(2, maximumActive.get())
        assertEquals(6, started.get())
    }

    @Test
    fun convertsOwnedResponseTimeoutToRetryableException() {
        assertFailsWith<ModelResponseTimeoutException> {
            runBlocking {
                withModelResponseTimeout(10.milliseconds, "测试响应") {
                    delay(1.seconds)
                }
            }
        }
    }

    @Test
    fun preservesOuterCoroutineTimeoutCancellation() {
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            runBlocking {
                withTimeout(10.milliseconds) {
                    withModelResponseTimeout(1.seconds, "测试响应") {
                        delay(1.seconds)
                    }
                }
            }
        }
    }
}
