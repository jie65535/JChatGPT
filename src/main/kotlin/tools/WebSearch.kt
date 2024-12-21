package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.apache.commons.text.StringEscapeUtils
import top.jie65535.mirai.PluginConfig

class WebSearch : BaseAgent(
    tool = Tool.function(
        name = "search",
        description = "Provides meta-search functionality through SearXNG," +
                " aggregating results from multiple search engines.",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("q") {
                    put("type", "string")
                    put("description", "查询内容关键字")
                }
                putJsonObject("categories") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add("general")
                            add("images")
                            add("videos")
                            add("news")
                            add("music")
                            add("it")
                            add("science")
                            add("files")
                            add("social_media")
                        }
                    }
                    put("description", "可选择多项查询分类，通常情况下不传或用general即可。")
                }
                putJsonObject("time_range") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("day")
                        add("month")
                        add("year")
                    }
                    put("description", "可选择获取最新消息，例如day表示只查询最近一天相关信息，以此类推。")
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
        val categories = args["categories"]?.jsonArray
        val timeRange = args["time_range"]?.jsonPrimitive?.contentOrNull
        val response = httpClient.get(
            buildString {
                append(PluginConfig.searXngUrl)
                append("?q=")
                append(q.encodeURLParameter())
                append("&format=json")
                if (categories != null) {
                    append("&")
                    append(categories.joinToString { it.jsonPrimitive.content })
                }
                if (timeRange != null) {
                    append("&")
                    append(timeRange)
                }
            }
        )
        val body = response.bodyAsText()
        val unescapedBody = StringEscapeUtils.unescapeJava(body)
        val responseJsonElement = Json.parseToJsonElement(unescapedBody)
        return buildJsonObject {
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
    }
}