package top.jie65535.mirai.conversation

import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import top.jie65535.mirai.llm.ModelService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class DeepSeekThinkingConversationTest {
    private val service = ModelService(
        baseUrl = "https://api.deepseek.com/v1/",
        token = "test",
        timeout = 1.seconds,
        firstChunkTimeout = 1.seconds,
        extraBody = Json.parseToJsonElement("""{"thinking":{"type":"enabled"}}""").jsonObject,
    )
    private val dateTool = Tool.function(
        name = "getDate",
        description = "获取日期",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        },
    )

    @Test
    fun returnsCompleteReasoningAndOrderedToolResultsAcrossThinkingTurns() {
        val toolDelta = assertNotNull(assertNotNull(service.decodeStreamChunk(
            """{"id":"chunk-1","created":1,"model":"deepseek-chat","choices":[{"index":0,"delta":{"reasoning_content":"先查询日期。","tool_calls":[{"index":0,"id":"date-1","type":"function","function":{"name":"getDate","arguments":"{}"}}]}}]}"""
        )).choices.single().delta)
        val call = assertNotNull(toolDelta.toolCalls).single().let {
            ToolCall.Function(assertNotNull(it.id), assertNotNull(it.function))
        }
        val toolMessage = buildConversationAssistantMessage(null, listOf(call), toolDelta.reasoningContent)
        val plainDelta = assertNotNull(assertNotNull(service.decodeStreamChunk(
            """{"id":"chunk-2","created":2,"model":"deepseek-chat","choices":[{"index":0,"delta":{"reasoning_content":"结合日期继续思考。\n保留换行。","content":"准备回答"}}]}"""
        )).choices.single().delta)
        val plainMessage = buildConversationAssistantMessage(plainDelta.content, emptyList(), plainDelta.reasoningContent)
        assertNull(plainMessage.toolCalls)

        val history = listOf(
            ChatMessage.User("今天几号？"),
            toolMessage,
            ChatMessage(role = ChatRole.Tool, content = "2026-11-10", toolCallId = call.id),
            plainMessage,
            ChatMessage.User(CONVERSATION_TOOL_PROTOCOL),
        )
        val body = Json.parseToJsonElement(service.buildRequestBody(
            buildConversationRequest(history, "deepseek-chat", null, listOf(dateTool)),
            stream = true,
        )).jsonObject

        assertEquals("auto", body.getValue("tool_choice").jsonPrimitive.content)
        assertEquals(1, body.getValue("tools").jsonArray.size)
        val messages = body.getValue("messages").jsonArray.drop(1).map { it.jsonObject }
        assertEquals("先查询日期。", messages[1].getValue("reasoning_content").jsonPrimitive.content)
        assertEquals("date-1", messages[1].getValue("tool_calls").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("date-1", messages[2].getValue("tool_call_id").jsonPrimitive.content)
        assertEquals("2026-11-10", messages[2].getValue("content").jsonPrimitive.content)
        assertEquals("结合日期继续思考。\n保留换行。", messages[3].getValue("reasoning_content").jsonPrimitive.content)
        assertEquals("准备回答", messages[3].getValue("content").jsonPrimitive.content)
        assertNull(messages[3]["tool_calls"])
    }

    @Test
    fun keepsToolSchemasAndHistoryPrefixesStableAcrossTurns() {
        val tools = listOf(dateTool)
        val history = mutableListOf(ChatMessage.User("今天几号？"))
        val first = Json.parseToJsonElement(service.buildRequestBody(
            buildConversationRequest(history, "deepseek-chat", null, tools), stream = true,
        )).jsonObject
        history += buildConversationAssistantMessage("准备回答", emptyList(), "思考内容原样保留。")
        history += ChatMessage.User(CONVERSATION_TOOL_PROTOCOL)
        val second = Json.parseToJsonElement(service.buildRequestBody(
            buildConversationRequest(history, "deepseek-chat", null, tools), stream = true,
        )).jsonObject

        assertEquals(first["tools"], second["tools"])
        assertEquals(first["tool_choice"], second["tool_choice"])
        val originalMessages = first.getValue("messages").jsonArray
        assertEquals(originalMessages.toList(), second.getValue("messages").jsonArray.take(originalMessages.size))
    }
}
