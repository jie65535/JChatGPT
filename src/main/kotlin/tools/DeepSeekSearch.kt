package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.*

internal class DeepSeekSearch : DeepSeekToolAgent(
    operation = "search",
    tool = Tool.function(
        name = "deepseekSearch",
        description = "Search via DeepSeek with query-focused summaries; prefer for general web searches.",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("minLength", 1)
                    put("maxLength", 128)
                    put("description", "搜索词，不能全为空白；支持 site:、-site:、引号、lang:、after:、before: 等语法。")
                }
                putJsonObject("count") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", 50)
                    put("default", 10)
                    put("description", "返回结果条数。")
                }
                putJsonObject("ori_question") {
                    put("type", "string")
                    put("nullable", true)
                    put("minLength", 1)
                    put("maxLength", 128)
                    put("description", "可选的用户原始问题，帮助理解搜索意图；null 视为未提供。")
                }
                putJsonObject("localization") {
                    put("type", "string")
                    put("default", "")
                    put("description", "用于优先返回本地化结果的 IP 地址；未提供时不指定本地化。")
                }
                putJsonObject("need_content") {
                    put("type", "boolean")
                    put("default", false)
                    put("description", "是否额外返回网页正文；为 false 时仍返回 snippet 和 summary。")
                }
            }
            putJsonArray("required") { add("query") }
        },
    ),
) {
    override val loadingMessage: String
        get() = "搜索中..."

    override fun validateArguments(args: JsonObject): JsonObject {
        requireString(args, "query", 128).also {
            require(it.isNotBlank()) { "query 不能全为空白" }
        }
        args["count"]?.let {
            val primitive = it as? JsonPrimitive
            require(primitive != null && !primitive.isString && primitive.intOrNull in 1..50) {
                "count 必须是 1 到 50 的整数"
            }
        }
        args["ori_question"]?.takeUnless { it == JsonNull }?.let {
            requireString(args, "ori_question", 128)
        }
        args["localization"]?.let {
            require(it is JsonPrimitive && it.isString) { "localization 必须是 IP 地址字符串" }
        }
        args["need_content"]?.let {
            require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) {
                "need_content 必须是布尔值"
            }
        }
        return args
    }
}
