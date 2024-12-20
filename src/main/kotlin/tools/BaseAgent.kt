package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import kotlinx.serialization.json.JsonObject

abstract class BaseAgent(
    val tool: Tool
) {
    open val isEnabled: Boolean = true

    abstract suspend fun execute(args: JsonObject): String

    override fun toString(): String {
        return "${tool.function.name}: ${tool.function.description}"
    }
}