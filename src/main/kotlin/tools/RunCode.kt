package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import top.jie65535.mirai.PluginConfig

class RunCode : BaseAgent(
    tool = Tool.function(
        name = "runCode",
        description = "执行代码，请尽量避免需要运行时输入或可能导致死循环的代码！",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("language") {
                    put("type", "string")
                    putJsonArray("enum") {
                        GLOT_LANGUAGES.forEach(::add)
                    }
                }
                putJsonObject("files") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("file") {
                            putJsonObject("name") {
                                put("type", "string")
                                put("description", "文件名，如 main.py")
                            }
                            putJsonObject("content") {
                                put("type", "string")
                                put("description", "文件内容，如 print(42)")
                            }
                        }
                    }
                    put("description", "代码文件")
                }
                putJsonObject("stdin") {
                    put("type", "string")
                    put("description", "可选的标准输入内容")
                }
            }
            putJsonArray("required") {
                add("language")
                add("files")
            }
        }
    )
) {
    companion object {
        /**
         * Glot API 地址
         */
        const val GLOT_RUN_API_URL = "https://glot.io/api/run/"

        /**
         * 使用的语言版本 仅有最新
         */
        const val GLOT_LANGUAGE_VERSION = "/latest"

        /**
         * Glot支持的编程语言（经过过滤和排序，实际支持40+，没必要）
         */
        val GLOT_LANGUAGES = listOf(
            "bash",
            "python",
            "c",
            "cpp",
            "csharp",
            "kotlin",
            "java",
            "javascript",
            "typescript",
            "go",
            "rust",
            "lua",
        )
    }

    /**
     * 设置了Token以后才启用
     */
    override val isEnabled: Boolean
        get() = PluginConfig.glotToken.isNotEmpty()

    override val loadingMessage: String
        get() = "执行代码中..."

    override suspend fun execute(args: JsonObject?): String {
        requireNotNull(args)
        val language = args.getValue("language").jsonPrimitive.content
        val filteredArgs = args.toMutableMap().let {
            it.remove("language")
            JsonObject(it)
        }
        val url = GLOT_RUN_API_URL + language + GLOT_LANGUAGE_VERSION
        val response = httpClient.post(url) {
            contentType(ContentType("application", "json"))
            header("Authorization", PluginConfig.glotToken)
            setBody(filteredArgs.toString())
        }
        return response.bodyAsText()
    }
}