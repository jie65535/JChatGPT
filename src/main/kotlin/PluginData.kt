package top.jie65535.mirai

import kotlinx.serialization.Serializable
import net.mamoe.mirai.console.data.AutoSavePluginData
import net.mamoe.mirai.console.data.value

/**
 * 好感度信息数据类
 * @param userId QQ
 * @param value 好感度值 (-100 ~ 100)
 * @param reasons 调整原因列表，用于溯源
 * @param impression 对用户的印象/画像
 * @param name Bot给此人起的代号
 * @param tags 标签列表，最多5个
 */
@Serializable
data class FavorabilityInfo(
    val userId: Long,
    val value: Int = 0,
    val reasons: List<String> = emptyList(),
    val impression: String = "",
    val name: String = "",
    val tags: List<String> = emptyList()
) {
    override fun toString(): String {
        return buildString {
            append("好感度：$value")
            if (name.isNotEmpty()) append("，代号：$name")
            if (tags.isNotEmpty()) append("，标签：[${tags.joinToString(", ")}]")
            if (impression.isNotEmpty()) append("，印象：$impression")
            if (reasons.isNotEmpty()) {
                appendLine()
                appendLine("调整原因：")
                reasons.forEach { reason ->
                    appendLine("* $reason")
                }
            }
        }
    }
}

/**
 * Token使用日聚合记录。按 (date, userId, groupId) 维度合并。由 [TokenUsageStore] 持久化到独立 JSON 文件。
 * @param date 本地时区下的日期，格式 yyyy-MM-dd
 * @param userId QQ
 * @param userNickname 最近一次记录到的昵称
 * @param groupId 群号（私聊时为null）
 * @param promptTokens 当天累计输入token
 * @param completionTokens 当天累计输出token
 * @param totalTokens 当天累计总token
 * @param callCount 当天调用次数
 */
@Serializable
data class TokenUsageDailyRecord(
    val date: String,
    val userId: Long,
    val userNickname: String,
    val groupId: Long?,
    /** 群名称，记录时捕获。展示时优先用它，避免暴露群号（被误判宣群）。私聊为 null。 */
    val groupName: String? = null,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
    /** 命中缓存的输入 token 数（DeepSeek: prompt_cache_hit_tokens）。缓存命中率 = cachedTokens / promptTokens */
    val cachedTokens: Long = 0,
    val callCount: Int = 0
)

object PluginData : AutoSavePluginData("data") {
    /**
     * 联系人记忆
     */
    val contactMemory by value(mutableMapOf<Long, String>())

    /**
     * 用户好感度数据
     * Key: 用户QQ号
     * Value: 好感度信息
     */
    val userFavorability by value(mutableMapOf<Long, FavorabilityInfo>())

    /**
     * 添加对话记忆
     */
    fun appendContactMemory(contactId: Long, newMemory: String) {
        val memory = contactMemory[contactId]
        if (memory.isNullOrEmpty()) {
            contactMemory[contactId] = newMemory
        } else {
            contactMemory[contactId] = "$memory\n$newMemory"
        }
    }

    /**
     * 替换对话记忆
     */
    fun replaceContactMemory(contactId: Long, oldMemory: String, newMemory: String) {
        val memory = contactMemory[contactId]
        if (memory.isNullOrEmpty()) {
            contactMemory[contactId] = newMemory
        } else {
            contactMemory[contactId] = memory.replace(oldMemory, newMemory)
                .replace("\n\n", "\n")
        }
    }
}
