package top.jie65535.mirai.profile

import top.jie65535.mirai.data.FavorabilityInfo

object UserProfileContextRenderer {
    fun render(
        profiles: List<UserProfileSnapshot>,
        favorabilityByUserId: Map<Long, FavorabilityInfo>,
        displayNames: Map<Long, String>,
        activeUserIds: Set<Long>,
        summaryMaxChars: Int,
        sectionTitle: String = "你对相关群友的认识",
    ): String {
        val profilesByUserId = profiles
            .filter { profile ->
                profile.reliable &&
                        (ProfilePersistentText.summaryForDisplay(profile.summary).isNotBlank() || profile.items.isNotEmpty())
            }
            .associateBy { it.userId }
        val userIds = activeUserIds.filter { userId ->
            userId in profilesByUserId || favorabilityByUserId[userId]?.hasVisibleContext() == true
        }
        if (userIds.isEmpty()) return ""

        val maxChars = summaryMaxChars.coerceAtLeast(50)
        return buildString {
            append("## ").appendLine(sectionTitle)
            appendLine("好感度、代号和主观印象代表你的关系状态；画像认识来自可修正的历史归纳。仅在当前话题相关时自然运用，不要逐条复述或提及信息来源。")
            userIds.forEach { userId ->
                val profile = profilesByUserId[userId]
                val favorability = favorabilityByUserId[userId]
                val name = favorability?.name.orEmpty().ifBlank {
                    displayNames[userId].orEmpty().ifBlank { userId.toString() }
                }
                append("- ").append(name).append('(').append(userId).append(')')
                favorability?.takeIf { it.hasVisibleContext() }?.let { info ->
                    append(" | 好感度").append(if (info.value >= 0) "+" else "").append(info.value)
                    if (info.tags.isNotEmpty()) append(" | 标签：").append(info.tags.joinToString("、"))
                    if (info.impression.isNotBlank()) append(" | 主观印象：").append(info.impression.normalized())
                }
                profile?.summary?.let(ProfilePersistentText::summaryForDisplay)
                    ?.takeIf(String::isNotBlank)?.let { summary ->
                    append(" | 画像认识")
                    append("（").append(profile.items.size).append("条）：")
                        .append(summary.normalized().take(maxChars))
                } ?: profile?.takeIf { it.items.isNotEmpty() }?.let {
                    append(" | 画像认识：已有").append(it.items.size).append("条记录，可用 queryUserProfile 查询详情")
                }

                profile?.items?.asSequence()
                    ?.filter { item ->
                        item.category == ProfileCategory.RELATIONSHIP_NOTE &&
                                item.relatedUserId != null && item.relatedUserId in activeUserIds
                    }
                    ?.take(2)
                    ?.forEach { item ->
                        val relatedId = checkNotNull(item.relatedUserId)
                        val relatedName = favorabilityByUserId[relatedId]?.name.orEmpty().ifBlank {
                            displayNames[relatedId].orEmpty().ifBlank { relatedId.toString() }
                        }
                        append("；与").append(relatedName).append("：")
                            .append(ProfilePersistentText.itemForDisplay(item.content, relationship = true).normalized())
                    }
                appendLine()
            }
            appendLine()
        }
    }

    private fun FavorabilityInfo.hasVisibleContext(): Boolean =
        value != 0 || name.isNotBlank() || tags.isNotEmpty() || impression.isNotBlank()

    private fun String.normalized(): String = trim().replace(Regex("\\s+"), " ")
}
