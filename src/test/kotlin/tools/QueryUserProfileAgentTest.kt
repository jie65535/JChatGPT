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
    fun formatsSupportCountWithoutRepeatingTheEvidenceRange() {
        val output = QueryUserProfileAgent().formatSupportStats(
            ProfileItemSupportStats(
                count = 4,
                firstSupportedAt = 1_735_689_600,
                lastSupportedAt = 1_751_472_000,
            )
        )

        assertEquals("4次支持", output)
        assertEquals("0次支持", QueryUserProfileAgent().formatSupportStats(null))
    }

    @Test
    fun formatsItemContentBeforeCompactMetadataWithOneConfirmationDate() {
        val output = QueryUserProfileAgent().formatProfileItem(
            item = UserProfileItem(
                id = "work",
                category = ProfileCategory.NOTABLE_FACT,
                content = "从事 Java 底层开发",
                confidence = ProfileConfidence.HIGH,
                firstSeenAt = 1_735_689_600,
                lastConfirmedAt = 1_751_472_000,
            ),
            supportStats = ProfileItemSupportStats(
                count = 4,
                firstSupportedAt = 1_735_689_600,
                lastSupportedAt = 1_751_472_000,
            ),
        )

        assertEquals(
            "从事 Java 底层开发〔事实，高置信；4次支持；最近确认于 2025-07-03〕",
            output,
        )
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
