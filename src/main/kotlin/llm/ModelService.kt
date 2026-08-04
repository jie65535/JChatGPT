package top.jie65535.mirai.llm

import com.aallam.openai.api.chat.ChatCompletionChunk
import com.aallam.openai.api.chat.ChatCompletionRequest
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.Dispatcher as OkHttpDispatcher
import okhttp3.Protocol as OkHttpProtocol
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.time.Duration

class ModelService(
    val baseUrl: String,
    val token: String,
    val timeout: Duration,
    val firstChunkTimeout: Duration,
    val extraBody: JsonObject? = null,
    maxConcurrentRequests: Int? = null,
) {
    private val maxConcurrentRequests = maxConcurrentRequests?.let(::normalizeMaxConcurrentRequests)
    private val requestSemaphore = this.maxConcurrentRequests?.let(::Semaphore)

    val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    protocols(MODEL_HTTP_PROTOCOLS)
                    this@ModelService.maxConcurrentRequests?.let { concurrencyLimit ->
                        dispatcher(createRequestDispatcher(concurrencyLimit))
                    }
                }
            }
            install(HttpTimeout) {
                // 流式响应的「首 token」与「token 间隔」超时统一由应用层计时管控（见 chatCompletions）。
                // 这里特意不设 requestTimeoutMillis：否则正常但耗时较长的流式输出会被 Ktor 在中途整体掐断。
                // socket 超时作为字节级兜底，连接超时只覆盖 TCP 握手。
                socketTimeoutMillis = timeout.inWholeMilliseconds
                connectTimeoutMillis = firstChunkTimeout.inWholeMilliseconds
            }
            defaultRequest {
                url(baseUrl)
                bearerAuth(token)
            }
            expectSuccess = true
        }
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** openai-kotlin 的 Usage 尚未暴露缓存明细，因此从原始 JSON 读取。 */
    data class CacheUsage(val hitTokens: Int, val missTokens: Int)

    /** 从原始 data 行（已去掉 "data: " 前缀）解析缓存命中用量；无相关字段返回 null。 */
    internal fun extractCacheUsage(rawJson: String): CacheUsage? {
        return try {
            val usage = json.parseToJsonElement(rawJson).jsonObject["usage"]?.jsonObject ?: return null
            val promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull
            val promptDetails = usage["prompt_tokens_details"]?.jsonObject
            val hit = usage["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
                ?: promptDetails?.get("cached_tokens")?.jsonPrimitive?.intOrNull
            val miss = usage["prompt_cache_miss_tokens"]?.jsonPrimitive?.intOrNull
                ?: if (hit != null && promptTokens != null) (promptTokens - hit).coerceAtLeast(0) else null
            if (hit == null && miss == null) null else CacheUsage(hit ?: 0, miss ?: 0)
        } catch (_: Exception) {
            null
        }
    }

    fun chatCompletions(
        request: ChatCompletionRequest,
        onCacheUsage: ((CacheUsage) -> Unit)? = null
    ): Flow<ChatCompletionChunk> {
        val body = buildRequestBody(request, stream = true)

        val responseFlow: Flow<ChatCompletionChunk> = flow {
            // 关键：服务器繁忙时会拖住「响应头」，使 httpClient.post() 自身阻塞在等待响应的阶段，
            // 因此必须把 post() 连同首个 data 块的读取一起纳入同一个应用层超时。
            // 否则首 token 超时永远不会触发（post() 还没返回，根本进不到读取循环），
            // 只能落到 Ktor 的兜底超时（很久）后再重试，表现为「等很久才报异常」。
            // channel 在超时块外层持有：哪怕首块读取超时，
            // 只要 response.body() 已拿到通道，finally 也能释放它，避免慢速 API 重试时连接泄漏。
            var channel: ByteReadChannel? = null
            var lineReader: LenientUtf8LineReader? = null
            try {
                val firstDataLine = withModelResponseTimeout(firstChunkTimeout, "首个响应数据块") {
                    val response = httpClient.post("chat/completions") {
                        setBody(body)
                        contentType(ContentType.Application.Json)
                        accept(ContentType.Text.EventStream)
                        headers {
                            append(HttpHeaders.CacheControl, "no-cache")
                            append(HttpHeaders.Connection, "keep-alive")
                        }
                    }
                    val ch: ByteReadChannel = response.body()
                    channel = ch
                    val reader = LenientUtf8LineReader(ch)
                    lineReader = reader
                    var found: String? = null
                    while (currentCoroutineContext().isActive) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("data: ")) {
                            found = line
                            break
                        }
                        // 心跳/空行/注释行，不计为首块，继续等
                    }
                    found
                }

                if (firstDataLine != null && !firstDataLine.startsWith("data: [DONE]")) {
                    val firstRaw = firstDataLine.removePrefix("data: ")
                    decodeStreamChunk(firstRaw)?.let { emit(it) }
                    onCacheUsage?.let { cb -> extractCacheUsage(firstRaw)?.let(cb) }

                    val ch = channel!!
                    val reader = lineReader!!
                    while (currentCoroutineContext().isActive) {
                        // 流式期间同样对每次读取设「token 间隔」超时，避免中途卡死后干等兜底超时，
                        // 从而能快速失败并交给上层重试。正常流式 token 间隔远小于 firstChunkTimeout。
                        val line = withModelResponseTimeout(firstChunkTimeout, "流式响应数据块") {
                            reader.readLine()
                        } ?: break
                        when {
                            line.startsWith("data: [DONE]") -> break
                            line.startsWith("data: ") -> {
                                val raw = line.removePrefix("data: ")
                                decodeStreamChunk(raw)?.let { emit(it) }
                                onCacheUsage?.let { cb -> extractCacheUsage(raw)?.let(cb) }
                            }
                            else -> continue
                        }
                    }
                }
            } finally {
                channel?.cancel()
            }
        }
        return responseFlow.withConcurrencyLimit(requestSemaphore)
    }

    internal fun buildRequestBody(request: ChatCompletionRequest, stream: Boolean): String {
        val requestJson = json.encodeToJsonElement(ChatCompletionRequest.serializer(), request)
            .jsonObject.toMutableMap()
        requestJson["stream"] = JsonPrimitive(stream)
        extraBody?.forEach { (key, value) ->
            requestJson[key] = value
        }
        return JsonObject(requestJson).toString()
    }

    internal fun decodeStreamChunk(rawJson: String): ChatCompletionChunk? {
        val element = try {
            json.parseToJsonElement(rawJson)
        } catch (cause: Exception) {
            throw ModelStreamProtocolException(
                "模型 SSE data 不是有效 JSON：${rawJson.toDiagnosticSnippet()}",
                cause,
            )
        }
        val payload = element as? JsonObject ?: throw ModelStreamProtocolException(
            "模型 SSE data 必须是 JSON object：${rawJson.toDiagnosticSnippet()}"
        )
        if (payload.isEmpty()) return null

        payload["error"]?.let { error ->
            val errorObject = error as? JsonObject
            val errorType = (errorObject?.get("type") as? JsonPrimitive)?.contentOrNull
            val errorCode = (errorObject?.get("code") as? JsonPrimitive)?.contentOrNull
            val errorMessage = (errorObject?.get("message") as? JsonPrimitive)?.contentOrNull
            val diagnostic = "模型 SSE 返回错误事件：${error.toString().toDiagnosticSnippet()}"
            if (isSafetyRejection(errorCode, errorMessage)) {
                throw ModelSafetyRejectionException(errorType, errorCode, diagnostic)
            }
            if (errorType == "invalid_request_error" &&
                isDeterministicRequestRejection(errorCode, errorMessage)
            ) {
                throw ModelRequestRejectedException(errorType, errorCode, diagnostic)
            }
            throw ModelStreamProtocolException(diagnostic)
        }

        val requiredChunkFields = listOf("id", "created", "model", "choices")
        val normalized = if (requiredChunkFields.all(payload::containsKey)) {
            payload
        } else if ("usage" in payload) {
            JsonObject(
                buildMap {
                    putAll(payload)
                    putIfAbsent("id", JsonPrimitive("usage-only"))
                    putIfAbsent("object", JsonPrimitive("chat.completion.chunk"))
                    putIfAbsent("created", JsonPrimitive(0))
                    putIfAbsent("model", JsonPrimitive("unknown"))
                    putIfAbsent("choices", JsonArray(emptyList()))
                }
            )
        } else {
            throw ModelStreamProtocolException(
                "模型 SSE 事件缺少 chunk 字段，keys=${payload.keys.sorted()}：" +
                        rawJson.toDiagnosticSnippet()
            )
        }

        return try {
            json.decodeFromJsonElement(ChatCompletionChunk.serializer(), normalized)
        } catch (cause: Exception) {
            throw ModelStreamProtocolException(
                "模型 SSE chunk 结构无效，keys=${payload.keys.sorted()}：" +
                        rawJson.toDiagnosticSnippet(),
                cause,
            )
        }
    }

    private fun String.toDiagnosticSnippet(maxChars: Int = 500): String =
        replace(Regex("[\\r\\n]+"), " ").let { normalized ->
            if (normalized.length <= maxChars) normalized else normalized.take(maxChars) + "...[截断]"
        }

    private fun isSafetyRejection(code: String?, message: String?): Boolean {
        val normalizedCode = code.orEmpty().lowercase()
        val normalizedMessage = message.orEmpty().lowercase()
        val explicitPolicyCode = normalizedCode == "cyber_policy" ||
                normalizedCode == "safety_policy" ||
                normalizedCode == "moderation_blocked" ||
                normalizedCode == "content_filter" ||
                normalizedCode.contains("content_policy")
        val explicitContentSafetyMessage =
            (normalizedMessage.contains("this content") &&
                    normalizedMessage.contains("safety reason")) ||
                    (normalizedMessage.contains("flagged for possible") &&
                            normalizedMessage.contains("risk")) ||
                    normalizedMessage.contains("biological risk") ||
                    normalizedMessage.contains("cybersecurity risk")
        return explicitPolicyCode || explicitContentSafetyMessage
    }

    private fun isDeterministicRequestRejection(code: String?, message: String?): Boolean {
        val normalizedCode = code.orEmpty().lowercase()
        val normalizedMessage = message.orEmpty().lowercase()
        return normalizedCode.contains("invalid_prompt") ||
                normalizedCode.contains("context_length") ||
                normalizedCode.contains("invalid_parameter") ||
                normalizedCode.contains("unsupported_parameter") ||
                normalizedMessage.contains("invalid prompt") ||
                normalizedMessage.contains("maximum context length") ||
                normalizedMessage.contains("unsupported parameter") ||
                normalizedMessage.contains("missing required parameter")
    }
}

internal class LenientUtf8LineReader(
    private val channel: ByteReadChannel,
) {
    private val readBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
    private val lineBuffer = ByteArrayOutputStream()
    private var readOffset = 0
    private var readLimit = 0
    private var skipLeadingLineFeed = false

    suspend fun readLine(): String? {
        while (true) {
            if (readOffset >= readLimit) {
                val read = channel.readAvailable(readBuffer)
                if (read < 0) {
                    channel.closedCause?.let { throw it }
                    return if (lineBuffer.size() == 0) null else decodeBufferedLine()
                }
                if (read == 0) continue
                readOffset = 0
                readLimit = read
            }

            if (skipLeadingLineFeed) {
                skipLeadingLineFeed = false
                if (readBuffer[readOffset] == LINE_FEED) {
                    readOffset++
                    continue
                }
            }

            val segmentStart = readOffset
            while (readOffset < readLimit) {
                val current = readBuffer[readOffset]
                if (current == CARRIAGE_RETURN || current == LINE_FEED) break
                readOffset++
            }
            if (readOffset > segmentStart) {
                lineBuffer.write(readBuffer, segmentStart, readOffset - segmentStart)
            }
            if (readOffset >= readLimit) continue

            val delimiter = readBuffer[readOffset++]
            if (delimiter == CARRIAGE_RETURN) skipLeadingLineFeed = true
            return decodeBufferedLine()
        }
    }

    private fun decodeBufferedLine(): String {
        val bytes = lineBuffer.toByteArray()
        lineBuffer.reset()
        // String(byte[], UTF_8) uses replacement semantics for malformed input instead of aborting the SSE stream.
        return String(bytes, Charsets.UTF_8)
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 8 * 1024
        const val CARRIAGE_RETURN: Byte = '\r'.code.toByte()
        const val LINE_FEED: Byte = '\n'.code.toByte()
    }
}

internal class ModelResponseTimeoutException(
    stage: String,
    timeout: Duration,
) : IOException("等待模型${stage}超过 ${timeout.inWholeMilliseconds} ms")

internal class ModelStreamProtocolException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal open class ModelRequestRejectedException(
    val errorType: String?,
    val errorCode: String?,
    message: String,
) : IOException(message)

internal class ModelSafetyRejectionException(
    errorType: String?,
    errorCode: String?,
    message: String,
) : ModelRequestRejectedException(errorType, errorCode, message)

internal suspend fun <T> withModelResponseTimeout(
    timeout: Duration,
    stage: String,
    block: suspend () -> T,
): T {
    val completed = withTimeoutOrNull(timeout) {
        CompletedModelResponse(block())
    } ?: throw ModelResponseTimeoutException(stage, timeout)
    return completed.value
}

private data class CompletedModelResponse<T>(val value: T)

internal const val MAX_MODEL_CONCURRENT_REQUESTS = 512
internal val MODEL_HTTP_PROTOCOLS = listOf(OkHttpProtocol.HTTP_1_1)

internal fun normalizeMaxConcurrentRequests(value: Int): Int =
    value.coerceIn(1, MAX_MODEL_CONCURRENT_REQUESTS)

internal fun createRequestDispatcher(maxConcurrentRequests: Int): OkHttpDispatcher =
    OkHttpDispatcher().apply {
        val concurrencyLimit = normalizeMaxConcurrentRequests(maxConcurrentRequests)
        maxRequests = concurrencyLimit
        maxRequestsPerHost = concurrencyLimit
    }

internal fun <T> Flow<T>.withConcurrencyLimit(semaphore: Semaphore?): Flow<T> {
    semaphore ?: return this
    return flow {
        semaphore.withPermit {
            this@withConcurrencyLimit.collect { value -> emit(value) }
        }
    }
}
