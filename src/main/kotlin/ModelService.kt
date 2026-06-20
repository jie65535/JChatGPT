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
                // 流式响应的「首 token」与「token 间隔」超时统一由应用层 withTimeout 管控（见 chatCompletions）。
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

    fun chatCompletions(request: ChatCompletionRequest): Flow<ChatCompletionChunk> {
        val requestJson = json.encodeToJsonElement(ChatCompletionRequest.serializer(), request)
            .jsonObject.toMutableMap()
        requestJson["stream"] = JsonPrimitive(true)
        extraBody?.forEach { (key, value) ->
            requestJson[key] = value
        }
        val body = JsonObject(requestJson).toString()

        return flow {
            // 关键：服务器繁忙时会拖住「响应头」，使 httpClient.post() 自身阻塞在等待响应的阶段，
            // 因此必须把 post() 连同首个 data 块的读取一起包进 withTimeout。
            // 否则首 token 超时永远不会触发（post() 还没返回，根本进不到读取循环），
            // 只能落到 Ktor 的兜底超时（很久）后再重试，表现为「等很久才报异常」。
            val (channel, firstDataLine) = withTimeout(firstChunkTimeout) {
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
                var found: String? = null
                while (currentCoroutineContext().isActive && !ch.isClosedForRead) {
                    val line = ch.readUTF8Line() ?: continue
                    if (line.startsWith("data: ")) {
                        found = line
                        break
                    }
                    // 心跳/空行/注释行，不计为首块，继续等
                }
                ch to found
            }

            try {
                if (firstDataLine != null && !firstDataLine.startsWith("data: [DONE]")) {
                    emit(json.decodeFromString(firstDataLine.removePrefix("data: ")))

                    while (currentCoroutineContext().isActive && !channel.isClosedForRead) {
                        // 流式期间同样对每次读取设「token 间隔」超时，避免中途卡死后干等兜底超时，
                        // 从而能快速失败并交给上层重试。正常流式 token 间隔远小于 firstChunkTimeout。
                        val line = withTimeout(firstChunkTimeout) { channel.readUTF8Line() } ?: continue
                        when {
                            line.startsWith("data: [DONE]") -> break
                            line.startsWith("data: ") -> {
                                emit(json.decodeFromString(line.removePrefix("data: ")))
                            }
                            else -> continue
                        }
                    }
                }
            } finally {
                channel.cancel()
            }
        }
    }
}
