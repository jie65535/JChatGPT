package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.mamoe.mirai.event.events.MessageEvent
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.data.ChatHistoryStore
import top.jie65535.mirai.data.ChatHistorySubject

class GetChatHistoryContext : BaseAgent(
    tool = Tool.function(
        name = "getChatHistoryContext",
        description = "读取某条聊天记录前后的消息。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("messageId") {
                    put("type", "integer")
                    put("description", "搜索结果中的 messageId")
                }
                putJsonObject("before") {
                    put("type", "integer")
                    put("description", "前文条数，默认8，最大15")
                }
                putJsonObject("after") {
                    put("type", "integer")
                    put("description", "后文条数，默认8，最大15")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("messageId")) }
        },
    )
) {
    override val isEnabled: Boolean
        get() = JChatGPT.includeHistory

    override val loadingMessage: String
        get() = "读取聊天记录上下文中..."

    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        val parameters = requireNotNull(args)
        val messageId = parameters["messageId"]?.jsonPrimitive?.longOrNull
            ?: return "缺少有效的 messageId"
        val before = parameters["before"]?.jsonPrimitive?.intOrNull?.coerceIn(0, MAX_CONTEXT) ?: DEFAULT_CONTEXT
        val after = parameters["after"]?.jsonPrimitive?.intOrNull?.coerceIn(0, MAX_CONTEXT) ?: DEFAULT_CONTEXT
        val context = try {
            ChatHistoryStore.findAround(
                subject = ChatHistorySubject.from(event.subject),
                messageId = messageId,
                before = before,
                after = after,
            )
        } catch (cause: Throwable) {
            JChatGPT.logger.warning("读取聊天记录上下文失败: messageId=$messageId", cause)
            return "读取聊天记录上下文失败: ${cause.message}"
        } ?: return "当前会话中不存在 messageId=$messageId 的消息"

        return buildString {
            appendLine("目标消息及上下文（共 ${context.records.size} 条）：")
            appendLine()
            ChatHistoryToolFormatter.appendRecords(
                output = this,
                records = context.records,
                event = event,
                targetId = context.targetId,
            )
        }.trimEnd()
    }

    companion object {
        private const val DEFAULT_CONTEXT = 8
        private const val MAX_CONTEXT = 15
    }
}
