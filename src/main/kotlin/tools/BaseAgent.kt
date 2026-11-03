package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import net.mamoe.mirai.event.events.MessageEvent

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

    protected open val retryOnConnectionFailure: Boolean = true

    /**
     * HTTP客户端
     */
    protected val httpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    retryOnConnectionFailure(this@BaseAgent.retryOnConnectionFailure)
                }
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 120_000
            }
        }
    }

    /**
     * 协程作用域
     */
    protected val scope by lazy {
        CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    open suspend fun execute(args: JsonObject?): String {
        return "OK"
    }

    open suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        return execute(args)
    }

    open fun limitOutput(content: String, maxLength: Int): String =
        if (content.length <= maxLength) content
        else content.take(maxLength) + "\n\n[系统提示：因内容过长，部分内容已被省略]"

    override fun toString(): String {
        return "${tool.function.name}: ${tool.function.description}"
    }
}
