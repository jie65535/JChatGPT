package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import kotlinx.serialization.json.JsonObject

abstract class BaseAgent(
    val tool: Tool
) {
    /**
     * 是否启用该工具
     */
    open val isEnabled: Boolean = true

    /**
     * 加载时消息  可用于提示用户正在执行
     */
    open val loadingMessage: String = ""

    protected val httpClient by lazy {
        HttpClient(OkHttp)
    }

    abstract suspend fun execute(args: JsonObject?): String

    override fun toString(): String {
        return "${tool.function.name}: ${tool.function.description}"
    }
}