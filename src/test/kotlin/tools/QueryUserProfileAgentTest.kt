package top.jie65535.mirai.tools

import top.jie65535.mirai.profile.ProfileCategory
import top.jie65535.mirai.profile.ProfileConfidence
import top.jie65535.mirai.profile.ProfileItemSupportStats
import top.jie65535.mirai.profile.UserProfileItem
import kotlin.test.Test
import kotlin.test.assertEquals

class QueryUserProfileAgentTest {
    @Test
    fun returnsAllItemsInStableOrder() {
        val items = listOf(
            item("later", firstSeenAt = 300),
            item("earlier", firstSeenAt = 100),
            item("middle", firstSeenAt = 200),
        )

        assertEquals(
            listOf("earlier", "middle", "later"),
            selectProfileItems(items).map(UserProfileItem::content),
        )
    }

    @Test
    fun formatsSupportCountAndEvidenceRange() {
        val output = QueryUserProfileAgent().formatSupportStats(
            ProfileItemSupportStats(
                count = 4,
                firstSupportedAt = 1_735_689_600,
                lastSupportedAt = 1_751_472_000,
            )
        )

        assertEquals("支持=4次（2025-01-01~2025-07-03）", output)
        assertEquals("支持=0次", QueryUserProfileAgent().formatSupportStats(null))
    }

    private fun item(content: String, firstSeenAt: Int) = UserProfileItem(
        id = content,
        category = ProfileCategory.NOTABLE_FACT,
        content = content,
        confidence = ProfileConfidence.MEDIUM,
        firstSeenAt = firstSeenAt,
        lastConfirmedAt = firstSeenAt,
    )
}
