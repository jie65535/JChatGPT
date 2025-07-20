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
import top.jie65535.mirai.PluginData

class MemoryReplace : BaseAgent(
    tool = Tool.Companion.function(
        name = "memoryReplace",
        description = "替换记忆项",
        parameters = Parameters.Companion.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("oldMemory") {
                    put("type", "string")
                    put("description", "原记忆项")
                }
                putJsonObject("newMemory") {
                    put("type", "string")
                    put("description", "新记忆项")
                }
            }
            putJsonArray("required") {
                add("oldMemory")
                add("newMemory")
            }
        }
    )
) {
    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        requireNotNull(args)
        val contactId = event.subject.id
        val oldMemoryText = args.getValue("oldMemory").jsonPrimitive.content
        val newMemoryText = args.getValue("newMemory").jsonPrimitive.content
        JChatGPT.logger.info("Replace memory ($contactId): \"$oldMemoryText\" -> \"$newMemoryText\"")
        PluginData.replaceContactMemory(contactId, oldMemoryText, newMemoryText)
        return "OK"
    }
}