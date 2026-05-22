package top.jie65535.mirai

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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.time.Duration

class ModelService(
    val baseUrl: String,
    val token: String,
    val timeout: Duration,
    val firstChunkTimeout: Duration,
    val extraBody: JsonObject? = null
) {
    val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                // 总请求/socket 超时保持长值，允许慢速流式输出；连接握手则用短超时。
                requestTimeoutMillis = timeout.inWholeMilliseconds
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

    fun chatCompletions(request: ChatCompletionRequest): Flow<ChatCompletionChunk> {
        val requestJson = json.encodeToJsonElement(ChatCompletionRequest.serializer(), request)
            .jsonObject.toMutableMap()
        requestJson["stream"] = JsonPrimitive(true)
        extraBody?.forEach { (key, value) ->
            requestJson[key] = value
        }
        val body = JsonObject(requestJson).toString()

        return flow {
            httpClient.post("chat/completions") {
                setBody(body)
                contentType(ContentType.Application.Json)
                accept(ContentType.Text.EventStream)
                headers {
                    append(HttpHeaders.CacheControl, "no-cache")
                    append(HttpHeaders.Connection, "keep-alive")
                }
            }.let { response ->
                val channel: ByteReadChannel = response.body()
                try {
                    // 首块 data: 必须在 firstChunkTimeout 内到达，否则抛 TimeoutCancellationException
                    // 走 JChatGPT 的重试流程；之后的流式读取不再有应用层超时，由 socketTimeoutMillis 兜底。
                    val firstDataLine: String? = withTimeout(firstChunkTimeout) {
                        var found: String? = null
                        while (currentCoroutineContext().isActive && !channel.isClosedForRead) {
                            val line = channel.readUTF8Line() ?: continue
                            if (line.startsWith("data: ")) {
                                found = line
                                break
                            }
                            // 心跳/空行/注释行，不计为首块，继续等
                        }
                        found
                    }

                    if (firstDataLine != null) {
                        if (!firstDataLine.startsWith("data: [DONE]")) {
                            emit(json.decodeFromString(firstDataLine.removePrefix("data: ")))

                            while (currentCoroutineContext().isActive && !channel.isClosedForRead) {
                                val line = channel.readUTF8Line() ?: continue
                                when {
                                    line.startsWith("data: [DONE]") -> break
                                    line.startsWith("data: ") -> {
                                        emit(json.decodeFromString(line.removePrefix("data: ")))
                                    }
                                    else -> continue
                                }
                            }
                        }
                    }
                } finally {
                    channel.cancel()
                }
            }
        }
    }
}
