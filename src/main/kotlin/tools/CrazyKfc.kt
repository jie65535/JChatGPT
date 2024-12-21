package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

class CrazyKfc : BaseAgent(
    tool = Tool.function(
        name = "crazyThursday",
        description = "获取一条KFC疯狂星期四文案",
        parameters = Parameters.Empty
    )
) {
    override suspend fun execute(args: JsonObject?): String {
        val response = httpClient.get("https://api.52vmy.cn/api/wl/yan/kfc")
        return response.bodyAsText()
    }
}