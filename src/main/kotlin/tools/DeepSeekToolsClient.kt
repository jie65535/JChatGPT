package top.jie65535.mirai.tools

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import top.jie65535.mirai.config.ModelProviderDefinition
import top.jie65535.mirai.util.RetryBackoff
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

internal class DeepSeekToolsClient(
    private val httpClient: HttpClient,
    private val logDiagnostic: (String) -> Unit = {},
    private val waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
) {
    internal class Endpoint(val api: String, val token: String, val provider: String)

    internal data class Response(val body: String, val billable: Boolean)

    suspend fun request(endpoint: Endpoint, operation: String, args: JsonObject): Response {
        require(operation == "search" || operation == "fetch")
        val semaphore = if (operation == "search") searchSemaphore else fetchSemaphore
        return semaphore.withPermit {
            val backoff = RetryBackoff(initialDelayMillis = 500, maxDelayMillis = 2_000)
            var retries = 0
            while (true) {
                val startedAt = System.nanoTime()
                logDiagnostic("DeepSeek $operation 开始第 ${retries + 1}/3 次请求")
                val response: HttpResponse
                val body: String
                try {
                    response = httpClient.post("${endpoint.api}/tools/$operation") {
                        bearerAuth(endpoint.token)
                        contentType(ContentType.Application.Json)
                        setBody(args.toString())
                    }
                    body = response.bodyAsText()
                } catch (cause: IOException) {
                    currentCoroutineContext().ensureActive()
                    val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
                    logDiagnostic("DeepSeek $operation 第 ${retries + 1}/3 次请求失败：${cause::class.simpleName}，耗时 ${elapsedMillis}ms")
                    if (retries >= 2) throw cause
                    retries++
                    val retryDelay = backoff.delayMillis(retries)
                    logDiagnostic("DeepSeek $operation 将在 ${retryDelay}ms 后重试（$retries/2）")
                    waitBeforeRetry(retryDelay)
                    continue
                }
                val status = response.status.value
                val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
                logDiagnostic("DeepSeek $operation 第 ${retries + 1}/3 次请求返回 HTTP $status，耗时 ${elapsedMillis}ms")
                if ((status == 408 || status == 429 || status in 500..599) && retries < 2) {
                    retries++
                    val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
                        ?.coerceIn(0, 60)?.times(1_000)
                    val retryDelay = maxOf(backoff.delayMillis(retries), retryAfter ?: 0)
                    logDiagnostic("DeepSeek $operation 将在 ${retryDelay}ms 后重试（$retries/2）")
                    waitBeforeRetry(retryDelay)
                    continue
                }
                val root = try {
                    Json.parseToJsonElement(body) as? JsonObject
                } catch (_: IllegalArgumentException) {
                    null
                }
                if (status == 200) {
                    val billable = when (operation) {
                        "search" -> (root?.get("results") as? JsonArray)?.isNotEmpty()
                        else -> (root?.get("content") as? JsonPrimitive)?.takeIf { it.isString }
                            ?.content?.isNotBlank()
                    } ?: return@withPermit Response(error("invalid_response", "DeepSeek $operation 响应格式无效"), false)
                    return@withPermit Response(body, billable)
                }
                val errorBody = if (root?.get("error") is JsonObject) body.replace(endpoint.token, "[REDACTED]")
                else error("http_error", "DeepSeek $operation 返回 HTTP $status")
                return@withPermit Response(errorBody, false)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    companion object {
        private val searchSemaphore = Semaphore(4)
        private val fetchSemaphore = Semaphore(2)

        internal fun resolveEndpoint(alias: String, providers: List<ModelProviderDefinition>): Endpoint? {
            if (alias.isBlank()) return null
            val provider = providers.singleOrNull { it.name.trim() == alias.trim() } ?: return null
            if (provider.token.isBlank() || provider.type.trim().lowercase() !in
                setOf("", "openai", "openai-compatible", "openai_compatible")) return null
            val uri = try {
                URI(provider.api.trim())
            } catch (_: URISyntaxException) {
                return null
            }
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            val base = provider.api.trim().trimEnd('/')
            val api = if (uri.path.trimEnd('/').endsWith("/v1")) base else "$base/v1"
            return Endpoint(api, provider.token.trim(), provider.name.trim())
        }

        internal fun error(type: String, message: String): String = buildJsonObject {
            putJsonObject("error") {
                put("message", message)
                put("type", type)
                put("param", JsonNull)
                put("code", type)
            }
        }.toString()
    }
}
