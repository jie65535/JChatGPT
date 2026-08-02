package top.jie65535.mirai.profile

import top.jie65535.mirai.data.FavorabilityInfo
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class UserProfileContextRendererTest {
    @Test
    fun rendersCompactSummariesAndOnlyRelationshipsInsideCurrentConversation() {
        val profile = UserProfileSnapshot(
            userId = 100,
            summary = "长期关注 Kotlin 开发，也经常讨论大模型应用。",
            cursorTime = 1,
            snapshotEndTime = 2,
            reliable = true,
            items = listOf(
                relationship("visible", 200, "经常互相讨论技术方案"),
                relationship("hidden", 300, "曾共同讨论游戏"),
            ),
        )

        val rendered = UserProfileContextRenderer.render(
            profiles = listOf(profile),
            favorabilityByUserId = mapOf(
                100L to FavorabilityInfo(
                    userId = 100,
                    value = 12,
                    name = "小明代号",
                    tags = listOf("老群友"),
                    impression = "聊天很直接",
                )
            ),
            displayNames = mapOf(100L to "小明", 200L to "小王", 300L to "小李"),
            activeUserIds = setOf(100, 200),
            summaryMaxChars = 50,
        )

        assertContains(rendered, "小明代号(100)")
        assertContains(rendered, "好感度+12")
        assertContains(rendered, "长期认识：长期关注 Kotlin 开发")
        assertContains(rendered, "与小王：经常互相讨论技术方案")
        assertFalse(rendered.contains("小李"))
        assertFalse(rendered.contains("曾共同讨论游戏"))
    }

    @Test
    fun keepsFavorabilityVisibleWithoutAHistoricalProfile() {
        val rendered = UserProfileContextRenderer.render(
            profiles = emptyList(),
            favorabilityByUserId = mapOf(
                100L to FavorabilityInfo(userId = 100, value = -8, impression = "偶尔喜欢抬杠")
            ),
            displayNames = mapOf(100L to "小明"),
            activeUserIds = setOf(100),
            summaryMaxChars = 300,
        )

        assertContains(rendered, "小明(100)")
        assertContains(rendered, "好感度-8")
        assertContains(rendered, "主观印象：偶尔喜欢抬杠")
        assertFalse(rendered.contains("长期认识："))
    }

    @Test
    fun keepsImpressionVisibleWhenFavorabilityIsZero() {
        val rendered = UserProfileContextRenderer.render(
            profiles = emptyList(),
            favorabilityByUserId = mapOf(
                100L to FavorabilityInfo(userId = 100, value = 0, impression = "长期活跃的老群友")
            ),
            displayNames = mapOf(100L to "小明"),
            activeUserIds = setOf(100),
            summaryMaxChars = 300,
        )

        assertContains(rendered, "小明(100)")
        assertContains(rendered, "好感度+0")
        assertContains(rendered, "主观印象：长期活跃的老群友")
    }

    private fun relationship(id: String, relatedUserId: Long, content: String) = UserProfileItem(
        id = id,
        category = ProfileCategory.RELATIONSHIP_NOTE,
        content = content,
        confidence = ProfileConfidence.MEDIUM,
        relatedUserId = relatedUserId,
        firstSeenAt = 1,
        lastConfirmedAt = 1,
    )
}
