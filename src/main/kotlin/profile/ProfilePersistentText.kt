package top.jie65535.mirai.profile

object ProfilePersistentText {
    fun normalizeSummary(raw: String, batch: ProfileHistoryBatch): String {
        var result = raw
        batch.aliases[batch.userId]?.let { result = replaceAlias(result, it, "该用户") }
        result = replaceAlias(result, "TARGET", "该用户")
        return normalize(result) { alias ->
            when (alias) {
                "TARGET" -> "该用户"
                "BOT" -> "机器人"
                else -> "其他用户"
            }
        }
    }

    fun normalizeItemContent(
        raw: String,
        batch: ProfileHistoryBatch,
        relatedUserId: Long?,
    ): String {
        var result = raw
        batch.aliases[batch.userId]?.let { result = replaceAlias(result, it, "本人") }
        result = replaceAlias(result, "TARGET", "本人")
        relatedUserId
            ?.let(batch.aliases::get)
            ?.let { result = replaceAlias(result, it, "对方") }
        return normalize(result) { alias -> if (alias == "BOT") "机器人" else "其他用户" }
    }

    fun summaryForDisplay(raw: String): String = normalize(raw) { alias ->
        when (alias) {
            "TARGET" -> "该用户"
            "BOT" -> "机器人"
            else -> "其他用户"
        }
    }

    fun itemForDisplay(raw: String, relationship: Boolean): String = normalize(raw) { alias ->
        when (alias) {
            "TARGET" -> "本人"
            "BOT" -> "机器人"
            else -> if (relationship) "对方" else "其他用户"
        }
    }

    private fun normalize(raw: String, replacement: (String) -> String): String = INTERNAL_ALIAS_PATTERN
        .replace(raw.trim()) { match -> replacement(match.value) }
        .replace(WHITESPACE_PATTERN, " ")
        .replace(CJK_SPACE_PATTERN, "")

    private fun replaceAlias(source: String, alias: String, replacement: String): String {
        if (alias.isBlank()) return source
        val pattern = Regex("(?<![A-Za-z0-9_])${Regex.escape(alias)}(?![A-Za-z0-9_])")
        return pattern.replace(source, replacement)
    }

    private val INTERNAL_ALIAS_PATTERN = Regex(
        "(?<![A-Za-z0-9_])(?:TARGET|BOT|U\\d+|R\\d+)(?![A-Za-z0-9_])"
    )
    private val WHITESPACE_PATTERN = Regex("\\s+")
    private val CJK_SPACE_PATTERN = Regex(
        "(?<=[\\p{IsHan}，。；：！？（）])\\s+(?=[\\p{IsHan}，。；：！？（）])"
    )
}
