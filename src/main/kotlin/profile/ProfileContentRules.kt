package top.jie65535.mirai.profile

object ProfileContentRules {
    fun validate(raw: String, label: String): String {
        val content = raw.trim()
        require(content.isNotEmpty()) { "$label 缺少 content" }
        require(content.length <= MAX_CONTENT_LENGTH) { "$label.content 超过 $MAX_CONTENT_LENGTH 字符" }
        require(!overclaimPattern.containsMatchIn(content)) { "$label.content 包含夸张身份或能力判断" }
        require(!itemReferencePattern.containsMatchIn(content)) { "$label.content 包含临时画像条目编号" }
        return content
    }

    fun validateSummary(raw: String, maxLength: Int): String {
        val summary = raw.trim()
        require(summary.length <= maxLength) { "画像摘要超过 $maxLength 字符" }
        require(!itemReferencePattern.containsMatchIn(summary)) { "画像摘要包含临时画像条目编号" }
        return summary
    }

    fun normalizedKey(value: String): String = value
        .lowercase()
        .replace(Regex("[\\s\\p{Punct}，。；、！？（）【】‘’“”]+"), "")

    private const val MAX_CONTENT_LENGTH = 120
    private val overclaimPattern = Regex("深厚|扎实|精通|专家|导师|领袖|天才|极强|全栈|核心成员|公认")
    private val itemReferencePattern = Regex("(?<![A-Za-z0-9_])P[1-9]\\d*(?![A-Za-z0-9_])")
}
