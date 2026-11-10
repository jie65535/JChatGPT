package top.jie65535.mirai.conversation

import com.aallam.openai.api.chat.FunctionCall
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationDiagnosticsTest {
    @Test
    fun previewsShortEmptyAndExactlyThirtyTwoCharacterOutputsInFull() {
        assertEquals("", formatToolResultPreview(""))
        assertEquals("完成", formatToolResultPreview("完成"))
        assertEquals("a".repeat(32), formatToolResultPreview("a".repeat(32)))
    }

    @Test
    fun truncatesAfterThirtyTwoUnicodeCodePointsAndShowsTotalLength() {
        val prefix = "a".repeat(31) + "😀"
        assertEquals("$prefix...（总长度 33 字符）", formatToolResultPreview(prefix + "后"))
        assertEquals("${"文".repeat(32)}...（总长度 100 字符）", formatToolResultPreview("文".repeat(100)))
    }

    @Test
    fun escapesLineBreaksAfterChoosingTheOriginalCharacterPreview() {
        assertEquals("a\\r\\n\\tb", formatToolResultPreview("a\r\n\tb"))
        assertEquals("${"a".repeat(31)}\\n...（总长度 33 字符）", formatToolResultPreview("a".repeat(31) + "\n后"))
    }

    @Test
    fun associatesToolsWithTheResponseRoundWithoutInventingAThinkingPhase() {
        val log = formatConversationRoundDiagnostics(
            trace = "run/2.1",
            model = "deepseek-chat",
            reasoningCharacters = 120,
            toolCalls = listOf(
                ToolCall.Function(ToolId("call-1"), FunctionCall("getDate", "{}")),
                ToolCall.Function(ToolId("call-2"), FunctionCall("sendSingleMessage", "{}")),
            ),
        )
        assertTrue(log.contains("[run/2.1]"))
        assertTrue(log.contains("思考内容=120字符"))
        assertTrue(log.contains("本轮工具=getDate#call-1, sendSingleMessage#call-2"))
        assertFalse(log.contains("思考阶段"))
        assertFalse(log.contains("tokens=120"))
        assertTrue(formatConversationRoundDiagnostics("run/3.1", "deepseek-chat", 0, emptyList()).endsWith("本轮工具=无"))
    }
}
