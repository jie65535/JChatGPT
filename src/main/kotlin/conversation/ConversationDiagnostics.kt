package top.jie65535.mirai.conversation

import com.aallam.openai.api.chat.ToolCall

internal fun formatConversationRoundDiagnostics(
    trace: String,
    model: String,
    reasoningCharacters: Int,
    toolCalls: List<ToolCall.Function>,
): String = buildString {
    append("[").append(trace).append("] 模型轮次完成 Model=").append(model)
    append("，思考内容=").append(reasoningCharacters).append("字符")
    append("，本轮工具=").append(toolCalls.joinToString { "${it.function.name}#${it.id.id}" }.ifEmpty { "无" })
}

internal fun formatToolResultPreview(content: String): String {
    val length = content.codePointCount(0, content.length)
    val preview = content.substring(0, content.offsetByCodePoints(0, minOf(length, 32)))
        .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
    return if (length > 32) "$preview...（总长度 $length 字符）" else preview
}
