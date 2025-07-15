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

class MemoryAgent : BaseAgent(
    tool = Tool.function(
        name = "remember",
        description = "更新当前记忆块。新增记忆时请带上原记忆，否则会被覆盖！",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("memory") {
                    put("type", "string")
                    put("description", "新记忆内容，无序列表文本块。")
                }
            }
            putJsonArray("required") {
                add("memory")
            }
        }
    )
) {
    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        requireNotNull(args)
        val contactId = event.subject.id
        val memoryText = args.getValue("memory").jsonPrimitive.content
        JChatGPT.logger.info("Remember ($contactId): $memoryText")
        PluginData.contactMemory[contactId] = memoryText

        return "OK"
    }
}