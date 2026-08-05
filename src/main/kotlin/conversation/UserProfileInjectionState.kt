package top.jie65535.mirai.conversation

internal class UserProfileInjectionState {
    private val renderedByUserId = mutableMapOf<Long, String?>()
    private var guidanceInjected = false

    fun hasSeen(userId: Long): Boolean = renderedByUserId.containsKey(userId)

    fun renderChanges(
        candidateUserIds: Collection<Long>,
        renderedEntries: Map<Long, String>,
        sectionTitle: String,
    ): String {
        val changedEntries = mutableListOf<String>()
        val clearedUserIds = mutableListOf<Long>()

        candidateUserIds.distinct().forEach { userId ->
            val current = renderedEntries[userId]
            if (!renderedByUserId.containsKey(userId)) {
                renderedByUserId[userId] = current
                if (current != null) changedEntries += current
                return@forEach
            }

            if (renderedByUserId[userId] == current) return@forEach
            renderedByUserId[userId] = current
            if (current == null) clearedUserIds += userId else changedEntries += current
        }

        if (changedEntries.isEmpty() && clearedUserIds.isEmpty()) return ""

        val firstInjection = !guidanceInjected
        guidanceInjected = true
        return buildString {
            append("## ").append(sectionTitle)
            if (!firstInjection) append("（更新）")
            appendLine()
            if (firstInjection) {
                appendLine(
                    "好感度、代号和主观印象代表你的关系状态；画像认识来自可修正的历史归纳。" +
                            "仅在当前话题相关时自然运用，不要逐条复述或提及信息来源。"
                )
            } else {
                appendLine("以下仅列出新增或发生变化的条目；同一用户以本段为准，未列出的认识保持不变。")
            }
            changedEntries.forEach(::append)
            clearedUserIds.forEach { userId ->
                append("- 用户(").append(userId)
                    .appendLine(")：当前已无可用的关系状态或画像认识，请忽略此前对应信息。")
            }
            appendLine()
        }
    }
}
