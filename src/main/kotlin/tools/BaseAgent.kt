package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
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

    /**
     * HTTP客户端
     */
    protected val httpClient by lazy {
        HttpClient(OkHttp)
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

    override fun toString(): String {
        return "${tool.function.name}: ${tool.function.description}"
    }
}