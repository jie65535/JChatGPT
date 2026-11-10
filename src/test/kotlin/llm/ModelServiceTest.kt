package top.jie65535.mirai.llm

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ToolChoice
import com.aallam.openai.api.core.Usage
import com.aallam.openai.api.model.ModelId
import com.sun.net.httpserver.HttpServer
import io.ktor.client.plugins.ClientRequestException
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
        val usage = service.extractUsageDetails(
            """{"usage":{"prompt_tokens":100,"prompt_tokens_details":{"cached_tokens":75}}}"""
        )

        assertEquals(ModelService.UsageDetails(hitTokens = 75, missTokens = 25), usage)
    }

    @Test
    fun readsDeepSeekCacheTokens() {
        val usage = service.extractUsageDetails(
            """{"usage":{"prompt_cache_hit_tokens":80,"prompt_cache_miss_tokens":20}}"""
        )

        assertEquals(ModelService.UsageDetails(hitTokens = 80, missTokens = 20), usage)
    }

    @Test
    fun ignoresResponsesWithoutCacheDetails() {
        assertNull(service.extractUsageDetails("""{"usage":{"prompt_tokens":100}}"""))
    }

    @Test
    fun readsReasoningTokensIndependentlyOfCacheFields() {
        assertEquals(
            ModelService.UsageDetails(reasoningTokens = 25),
            service.extractUsageDetails("""{"usage":{"completion_tokens":40,"completion_tokens_details":{"reasoning_tokens":25}}}"""),
        )
        assertEquals(
            ModelService.UsageDetails(reasoningTokens = 0),
            service.extractUsageDetails("""{"usage":{"completion_tokens_details":{"reasoning_tokens":0}}}"""),
        )
        for (invalid in listOf("null", "-1", "\"invalid\"", "{}", "[]")) {
            assertNull(service.extractUsageDetails("""{"usage":{"completion_tokens_details":{"reasoning_tokens":$invalid}}}"""))
        }
        assertEquals(
            ModelService.UsageDetails(hitTokens = 80, reasoningTokens = 25),
            service.extractUsageDetails("""{"usage":{"prompt_cache_hit_tokens":80,"prompt_tokens_details":[],"completion_tokens_details":{"reasoning_tokens":25}}}"""),
        )
    }

    @Test
    fun logsUsageOncePerRequestWithoutDoubleCountingOrReusingPreviousCounts() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requestCount = AtomicInteger()
        val firstResponse = listOf(
            """data: {"usage":{"prompt_tokens":100,"completion_tokens":40,"total_tokens":140,"prompt_cache_hit_tokens":80,"prompt_cache_miss_tokens":20}}""",
            """data: {"usage":{"completion_tokens_details":{"reasoning_tokens":25}}}""",
            """data: {"usage":{"completion_tokens_details":{"reasoning_tokens":25}}}""",
            "data: [DONE]",
            "",
        ).joinToString("\n\n")
        val secondResponse = listOf(
            """data: {"usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8,"completion_tokens_details":{"reasoning_tokens":0}}}""",
            "data: [DONE]",
            "",
        ).joinToString("\n\n")
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val count = requestCount.incrementAndGet()
            val body = when (count) {
                1 -> firstResponse
                2 -> secondResponse
                else -> """{"error":{"message":"invalid request"}}"""
            }.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", if (count < 3) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(if (count < 3) 200 else 400, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val logs = mutableListOf<String>()
        val localService = ModelService(
            baseUrl = "http://127.0.0.1:${server.address.port}/v1/",
            token = "test",
            timeout = 3.seconds,
            firstChunkTimeout = 3.seconds,
            usageLogger = logs::add,
        )
        val request = ChatCompletionRequest(model = ModelId("test-model"), messages = listOf(ChatMessage.User("hello")))
        try {
            var lastUsage: Usage? = null
            var details: ModelService.UsageDetails? = null
            localService.chatCompletions(request, trace = "run/1.1") { details = it }.collect { chunk ->
                chunk.usage?.let { lastUsage = it }
            }
            assertEquals(Usage(promptTokens = 100, completionTokens = 40, totalTokens = 140), lastUsage)
            assertEquals(ModelService.UsageDetails(80, 20, 25), details)
            assertEquals(1, logs.size)
            assertTrue(logs[0].contains("[run/1.1]"))
            assertTrue(logs[0].contains("输出tokens（含思考）=40"))
            assertTrue(logs[0].contains("其中思考tokens=25"))
            assertTrue(logs[0].contains("总tokens=140"))

            localService.chatCompletions(request, trace = "run/2.1").collect()
            assertEquals(2, logs.size)
            assertTrue(logs[1].contains("缓存命中tokens=未知"))
            assertTrue(logs[1].contains("其中思考tokens=0"))
            assertTrue(logs[1].contains("总tokens=8"))

            assertFailsWith<ClientRequestException> {
                localService.chatCompletions(request, trace = "run/3.1").collect()
            }
            assertEquals(3, logs.size)
            assertTrue(logs[2].contains("状态=未完成"))
            assertTrue(logs[2].contains("输入tokens=未知"))
            assertTrue(logs[2].contains("其中思考tokens=未知"))
            assertTrue(logs[2].contains("总tokens=未知"))
        } finally {
            localService.httpClient.close()
            server.stop(0)
        }
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
    fun serializesRequiredToolChoice() {
        val body = service.buildRequestBody(
            request = ChatCompletionRequest(
                model = ModelId("test-model"),
                messages = listOf(ChatMessage.User("hello")),
                toolChoice = ToolChoice.Required,
            ),
            stream = true,
        )

        assertEquals(
            "required",
            Json.parseToJsonElement(body).jsonObject.getValue("tool_choice").jsonPrimitive.content,
        )
    }

    @Test
    fun usesAutoForDeepSeekThinkingIncludingRelaysAndDefaultThinkingModels() {
        val enabled = """{"thinking":{"type":"enabled"}}"""
        for ((api, model, extra) in listOf(
            Triple("https://api.deepseek.com/v1/", "custom-model", enabled),
            Triple("https://relay.example/v1/", "deepseek-chat", enabled),
            Triple("https://relay.example/v1/", "deepseek/deepseek-reasoner", "{}"),
            Triple("https://api.deepseek.com/v1/", "deepseek-v4-pro", "{}"),
            Triple("https://api.deepseek.com/v1/", "deepseek-flash", "{}"),
        )) {
            val body = toolChoiceBody(api, model, extra)
            assertEquals(JsonPrimitive("auto"), body["tool_choice"], "$api $model")
            Json.parseToJsonElement(extra).jsonObject["thinking"]?.let {
                assertEquals(it, body["thinking"])
            }
        }
    }

    @Test
    fun preservesRequiredForNonThinkingDeepSeekAndOtherProviders() {
        for ((api, model, extra) in listOf(
            Triple("https://api.deepseek.com/v1/", "deepseek-reasoner", """{"thinking":{"type":"disabled"}}"""),
            Triple("https://api.deepseek.com/v1/", "deepseek-chat", "{}"),
            Triple("https://other.example/v1/", "other-model", """{"thinking":{"type":"enabled"}}"""),
            Triple("https://api.deepseek.com.example/v1/", "other-model", """{"thinking":{"type":"enabled"}}"""),
        )) {
            assertEquals(JsonPrimitive("required"), toolChoiceBody(api, model, extra)["tool_choice"], "$api $model")
        }
    }

    @Test
    fun preservesAutoNoneAndUnspecifiedChoicesForDeepSeekThinking() {
        for (choice in listOf("\"auto\"", "\"none\"", "null")) {
            val body = toolChoiceBody(
                "https://api.deepseek.com/v1/", "deepseek-reasoner",
                """{"tool_choice":$choice}""",
            )
            assertEquals(Json.parseToJsonElement(choice), body["tool_choice"])
        }
    }

    @Test
    fun normalizesNamedToolChoiceAfterMergingExtraBody() {
        val body = toolChoiceBody(
            "https://relay.example/v1/", "other-model",
            """{"model":"deepseek-chat","thinking":{"type":"enabled"},"tool_choice":{"type":"function","function":{"name":"getDate"}}}""",
        )
        assertEquals(JsonPrimitive("auto"), body["tool_choice"])

        val withoutChoice = ModelService(
            baseUrl = "https://api.deepseek.com/v1/",
            token = "test",
            timeout = 1.seconds,
            firstChunkTimeout = 1.seconds,
        ).buildRequestBody(
            ChatCompletionRequest(model = ModelId("deepseek-reasoner"), messages = listOf(ChatMessage.User("hello"))),
            stream = true,
        )
        assertNull(Json.parseToJsonElement(withoutChoice).jsonObject["tool_choice"])
    }

    private fun toolChoiceBody(api: String, model: String, extra: String): JsonObject =
        Json.parseToJsonElement(
            ModelService(
                baseUrl = api,
                token = "test",
                timeout = 1.seconds,
                firstChunkTimeout = 1.seconds,
                extraBody = Json.parseToJsonElement(extra).jsonObject,
            ).buildRequestBody(
                ChatCompletionRequest(
                    model = ModelId(model),
                    messages = listOf(ChatMessage.User("hello")),
                    toolChoice = ToolChoice.Required,
                ),
                stream = true,
            )
        ).jsonObject

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
    fun sendsRequestsContainingUnpairedSurrogates() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val receivedBody = AtomicReference<String>()
        val responseBody = listOf(
            """data: {"id":"chunk-1","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":null}]}""",
            "data: [DONE]",
            "",
        ).joinToString("\n\n").toByteArray()
        server.createContext("/v1/chat/completions") { exchange ->
            receivedBody.set(String(exchange.requestBody.readBytes(), Charsets.UTF_8))
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
                    messages = listOf(ChatMessage.User("emoji \uD83D\uDE00 before\uD800middle\uDC00after")),
                )
            ).collect { chunk ->
                chunk.choices.firstOrNull()?.delta?.content?.let(content::append)
            }

            assertEquals("ok", content.toString())
            assertTrue(receivedBody.get().contains("emoji \uD83D\uDE00 before\uFFFDmiddle\uFFFDafter"))
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
