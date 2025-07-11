package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.*
import net.mamoe.mirai.event.events.MessageEvent
import top.jie65535.mirai.JChatGPT

class SendSingleMessageAgent : BaseAgent(
    tool = Tool.function(
        name = "sendSingleMessage",
        description = "发送一条消息，适合发送一行以内的短句（不支持Markdown）",
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
        event.subject.sendMessage(JChatGPT.toMessage(event.subject, content))
        return "OK"
    }
}