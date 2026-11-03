package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URISyntaxException

internal class DeepSeekFetch : DeepSeekToolAgent(
    operation = "fetch",
    tool = Tool.function(
        name = "deepseekFetch",
        description = "Fetch webpage titles and Markdown via DeepSeek; prefer for reading public webpages.",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("url") {
                    put("type", "string")
                    put("minLength", 1)
                    put("maxLength", 8192)
                    put("description", "要抓取的网页地址，必须为含主机名的 HTTP/HTTPS URL，不能携带用户名或密码。")
                }
            }
            putJsonArray("required") { add("url") }
        },
    ),
) {
    override val loadingMessage: String
        get() = "上网中..."

    override fun validateArguments(args: JsonObject): JsonObject {
        val url = requireString(args, "url", 8192)
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("url 必须是有效的 HTTP/HTTPS URL")
        }
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) {
            "url 必须是含主机名的 HTTP/HTTPS URL"
        }
        require(uri.rawUserInfo == null) { "url 不能携带用户名或密码" }
        return args
    }
}
