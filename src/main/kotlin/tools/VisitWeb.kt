package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import top.jie65535.mirai.config.PluginConfig
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

class VisitWeb : BaseAgent(
    tool = Tool.function(
        name = "visit",
        description = "Visit webpage(s) and return the summary of the content.",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("url") {
                    putJsonArray("type") {
                        add("string")
                        add("array")
                    }
                    putJsonObject("items") {
                        put("type", "string")
                    }
                    put("minItems", 1)
                    put("description", "The URL(s) of the webpage(s) to visit. Can be a single URL or an array of URLs.")
                }
            }

            putJsonArray("required") {
                add("url")
            }
        }
    )
) {
    companion object {
        internal data class ReaderRequest(
            val endpoint: String,
            val apiKey: String?,
        )

        internal fun createReaderRequest(
            rawUrl: String,
            readerBaseUrl: String,
            apiKey: String,
            resolveAddresses: (String) -> List<InetAddress> = {
                InetAddress.getAllByName(it).toList()
            },
        ): ReaderRequest {
            val target = parseHttpUrl(rawUrl, "网页地址")
            require(target.userInfo == null) { "网页地址不能包含用户凭据" }

            val targetHost = requireNotNull(target.host).removeSurrounding("[", "]").lowercase()
            require(targetHost.contains('.') || targetHost.contains(':')) {
                "禁止访问单标签或内部主机名"
            }
            require(
                targetHost != "localhost" &&
                        !targetHost.endsWith(".localhost") &&
                        !targetHost.endsWith(".local") &&
                        !targetHost.endsWith(".internal") &&
                        !targetHost.endsWith(".lan") &&
                        !targetHost.endsWith(".home.arpa")
            ) { "禁止访问本机或局域网网页地址" }

            val addresses = try {
                resolveAddresses(targetHost)
            } catch (_: UnknownHostException) {
                // 目标可能只能由 Mihomo 的 DNS 解析，交给代理继续处理。
                emptyList()
            }
            require(addresses.all(VisualImageResolver::isPublicAddress)) {
                "网页地址解析到非公网地址，已拒绝访问"
            }

            val readerBase = parseHttpUrl(readerBaseUrl, "Jina Reader API 地址")
            require(readerBase.query == null && readerBase.fragment == null) {
                "Jina Reader API 地址不能包含查询参数或片段"
            }

            return ReaderRequest(
                endpoint = readerBaseUrl.trim().trimEnd('/') + "/" +
                        target.toASCIIString().substringBefore('#'),
                apiKey = apiKey.trim().takeIf(String::isNotEmpty),
            )
        }

        private fun parseHttpUrl(rawUrl: String, label: String): URI {
            val uri = try {
                URI(rawUrl.trim())
            } catch (e: Exception) {
                throw IllegalArgumentException("$label 格式无效", e)
            }
            require(uri.scheme?.lowercase() in setOf("http", "https")) {
                "$label 仅支持 HTTP/HTTPS"
            }
            require(!uri.host.isNullOrBlank()) { "$label 缺少有效主机名" }
            return uri.normalize()
        }
    }

    override val isEnabled: Boolean
        get() = true // PluginConfig.jinaApiKey.isNotEmpty()

    override val loadingMessage: String
        get() = "上网中..."

    override suspend fun execute(args: JsonObject?): String {
        requireNotNull(args)
        val urlJson = args.getValue("url")
        if (urlJson is JsonPrimitive) {
            return jinaReadPage(urlJson.content)
        } else if (urlJson is JsonArray) {
            return urlJson.map {
                scope.async { jinaReadPage(it.jsonPrimitive.content) }
            }.awaitAll().joinToString()
        }
        return ""
    }

    private suspend fun jinaReadPage(url: String): String {
        return try {
            val request = withContext(Dispatchers.IO) {
                createReaderRequest(
                    rawUrl = url,
                    readerBaseUrl = PluginConfig.jinaReaderUrl,
                    apiKey = PluginConfig.jinaApiKey,
                )
            }
            val response = httpClient.get(request.endpoint) {
                header(HttpHeaders.Accept, ContentType.Text.Plain)
                request.apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                body
            } else {
                "Error fetching \"$url\": HTTP ${response.status.value} ${body.take(500)}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error fetching \"$url\": ${e.message}"
        }
    }
}
