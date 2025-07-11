package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.*
import top.jie65535.mirai.PluginConfig

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
        // Visit Tool (Using Jina Reader)
        const val JINA_READER_URL_PREFIX = "https://r.jina.ai/"
    }

    override val isEnabled: Boolean
        get() = PluginConfig.jinaApiKey.isNotEmpty()

    override val loadingMessage: String
        get() = "访问网页中..."

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
            httpClient.get(JINA_READER_URL_PREFIX + url) {
                header("Authorization", "Bearer ${PluginConfig.jinaApiKey}")
            }.bodyAsText()
        } catch (e: Throwable) {
            "Error fetching \"$url\": ${e.message}"
        }
    }
}