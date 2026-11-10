package top.jie65535.mirai.llm

import com.aallam.openai.api.core.Usage

internal fun formatModelUsageLog(
    trace: String,
    model: String,
    usage: Usage?,
    details: ModelService.UsageDetails,
    completed: Boolean,
): String = buildString {
    append("[").append(trace).append("] 模型调用用量 Model=").append(model)
    append("，状态=").append(if (completed) "完成" else "未完成（仅已报告用量）")
    append("，输入tokens=").append(usage?.promptTokens ?: "未知")
    append("，缓存命中tokens=").append(details.hitTokens ?: "未知")
    append("，缓存未命中tokens=").append(details.missTokens ?: "未知")
    append("，输出tokens（含思考）=").append(usage?.completionTokens ?: "未知")
    append("，其中思考tokens=").append(details.reasoningTokens ?: "未知")
    val promptTokens = usage?.promptTokens
    val completionTokens = usage?.completionTokens
    val total = usage?.totalTokens?.toLong() ?: if (promptTokens != null && completionTokens != null) {
        promptTokens.toLong() + completionTokens
    } else null
    append("，总tokens=").append(total ?: "未知")
}
