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
import kotlinx.serialization.json.*
import kotlin.time.Duration

class ModelService(
    val baseUrl: String,
    val token: String,
    val timeout: Duration,
    val extraBody: JsonObject? = null
) {
    val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                val millis = timeout.inWholeMilliseconds
                requestTimeoutMillis = millis
                connectTimeoutMillis = millis
                socketTimeoutMillis = millis
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
                    while (currentCoroutineContext().isActive && !channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: continue
                        when {
                            line.startsWith("data: [DONE]") -> break
                            line.startsWith("data: ") -> {
                                val chunk = json.decodeFromString<ChatCompletionChunk>(
                                    line.removePrefix("data: ")
                                )
                                emit(chunk)
                            }
                            else -> continue
                        }
                    }
                } finally {
                    channel.cancel()
                }
            }
        }
    }
}
