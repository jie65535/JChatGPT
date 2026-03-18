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
 */
@Serializable
data class FavorabilityInfo(
    val userId: Long,
    val value: Int = 0,
    val reasons: List<String> = emptyList(),
    val impression: String = ""
) {
    override fun toString(): String {
        return buildString {
            append("好感度：$value")
            if (impression.isNotEmpty()) {
                append("\t印象：$impression")
            }
            if (reasons.isNotEmpty()) {
                appendLine("\t调整原因：")
                reasons.forEach { reason ->
                    appendLine("* $reason")
                }
            }
        }
    }
}

/**
 * Token使用记录数据类
 * @param timestamp Unix时间戳
 * @param userId 用户QQ号
 * @param userNickname 用户昵称
 * @param groupId 群号（私聊时为null）
 * @param model 模型名称
 * @param promptTokens 输入token数
 * @param completionTokens 输出token数
 * @param totalTokens 总token数
 */
@Serializable
data class TokenUsageRecord(
    val timestamp: Long,
    val userId: Long,
    val userNickname: String,
    val groupId: Long?,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
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
     * Token使用记录
     */
    val tokenUsageRecords by value(mutableListOf<TokenUsageRecord>())

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