package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.mamoe.mirai.event.events.MessageEvent
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.PluginConfig
import top.jie65535.mirai.PluginData

class MemoryAppend : BaseAgent(
    tool = Tool.function(
        name = "memoryAppend",
        description = "新增记忆项",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("memory") {
                    put("type", "string")
                    put("description", "记忆项")
                }
            }
            putJsonArray("required") {
                add("memory")
            }
        }
    )
) {
    override val isEnabled: Boolean
        get() = PluginConfig.memoryEnabled

    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        requireNotNull(args)
        val contactId = event.subject.id
        val memoryText = args.getValue("memory").jsonPrimitive.content
        JChatGPT.logger.info("Remember ($contactId): \"$memoryText\"")
        PluginData.appendContactMemory(contactId, memoryText)
        return "OK"
    }
}