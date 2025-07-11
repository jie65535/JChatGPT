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
import net.mamoe.mirai.message.data.buildForwardMessage
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.PluginConfig
import kotlin.collections.getValue

class SendCompositeMessage : BaseAgent(
    tool = Tool.function(
        name = "sendCompositeMessage",
        description = "发送组合消息，适合发送较长消息而避免刷屏（不支持Markdown）",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "消息内容")
                }
            }
            putJsonArray("required") {
                add("content")
            }
        }
    )
) {
    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        requireNotNull(args)
        val content = args.getValue("content").jsonPrimitive.content
        val msg = JChatGPT.toMessage(event.subject, content)
        event.subject.sendMessage(
            if (content.length > PluginConfig.messageMergeThreshold) {
                event.buildForwardMessage {
                    event.bot says msg
                }
            } else {
                msg
            }
        )
        return "OK"
    }
}