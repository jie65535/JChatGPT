package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.apache.commons.text.StringEscapeUtils
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.PluginConfig

class WebSearch : BaseAgent(
    tool = Tool.function(
        name = "webSearch",
        description = "Provides meta-search functionality through SearXNG," +
                " aggregating results from multiple search engines.",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("q") {
                    put("type", "string")
                    put("description", "查询内容关键字")
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

    override val loadingMessage: String
        get() = "联网搜索中..."

    override suspend fun execute(args: JsonObject?): String {
        requireNotNull(args)
        val q = args.getValue("q").jsonPrimitive.content
        val url = buildString {
            append(PluginConfig.searXngUrl)
            append("?q=")
            append(q.encodeURLParameter())
            append("&format=json")
        }

        val response = httpClient.get(url)
        JChatGPT.logger.info("Request: $url")
        val body = response.bodyAsText()
        JChatGPT.logger.info("Response: $body")
        val responseJsonElement = Json.parseToJsonElement(body)
        val filteredResponse = buildJsonObject {
            val root = responseJsonElement.jsonObject
            // 查询内容原样转发
            root["query"]?.let { put("query", it) }

            // 过滤搜索结果
            val results = root["results"]?.jsonArray
            if (results != null) {
                val filteredResults = results
                    .filter {
                        // 去掉所有内容为空的结果
                        !it.jsonObject.getValue("content").jsonPrimitive.contentOrNull.isNullOrEmpty()
                    }.sortedByDescending {
                        it.jsonObject.getValue("score").jsonPrimitive.double
                    }.take(5) // 只取得分最高的前5条结果
                    .map {
                        // 移除掉我不想要的字段
                        val item = it.jsonObject.toMutableMap()
                        item.remove("engine")
                        item.remove("parsed_url")
                        item.remove("template")
                        item.remove("engines")
                        item.remove("positions")
                        item.remove("metadata")
                        item.remove("thumbnail")
                        JsonObject(item)
                    }
                put("results", JsonArray(filteredResults))
            }

            // 答案和信息盒子原样转发
            root["answers"]?.let { put("answers", it) }
            root["infoboxes"]?.let { put("infoboxes", it) }
        }.toString()
        return StringEscapeUtils.unescapeJava(filteredResponse)
    }
}