package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.*
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.message.data.Message
import net.mamoe.mirai.message.data.QuoteReply
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
                putJsonObject("replyTo") {
                    put("type", "integer")
                    put("description", "可选。要引用回复的历史消息编号（即历史记录中每行行首的[n]）。不需要回复具体某条消息时省略此参数。")
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
        val replyTo = args["replyTo"]?.jsonPrimitive?.intOrNull

        val baseMsg = JChatGPT.toMessage(event.subject, content)
        var note = ""
        val message: Message = if (replyTo != null) {
            val record = JChatGPT.lookupReplyTarget(event.subject.id, replyTo)
            val source = try {
                record?.toMessageSource()
            } catch (e: Throwable) {
                null
            }
            if (source != null) {
                QuoteReply(source) + baseMsg
            } else {
                note = "（编号${replyTo}对应的消息已失效，未能引用，已直接发送）"
                baseMsg
            }
        } else {
            baseMsg
        }

        event.subject.sendMessage(message)
        return "OK$note"
    }
}
