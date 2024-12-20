package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import top.jie65535.mirai.PluginConfig
import org.apache.commons.text.StringEscapeUtils

class WebSearch : BaseAgent(
    tool = Tool.function(
        name = "search",
        description = "通过互联网搜索一切",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("q") {
                    put("type", "string")
                    put("description", "The search query")
                }
            }
            putJsonArray("required") {
                add("q")
            }
        }
    )
) {
    /**
     * 插件配置了 SearXNG URL 时才允许启用
     */
    override val isEnabled: Boolean
        get() = PluginConfig.searXngUrl.isNotEmpty()

    private val httpClient by lazy {
        HttpClient(OkHttp)
    }

    override suspend fun execute(args: JsonObject): String {
        val q = args.getValue("q").jsonPrimitive.content
        val response = httpClient.get(
            "${PluginConfig.searXngUrl}?q=${q.encodeURLParameter(true)}&format=json"
        )
        val body = response.bodyAsText()
        return StringEscapeUtils.unescapeJava(body)
    }
}