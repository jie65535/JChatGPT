package top.jie65535.mirai.tools

import top.jie65535.mirai.profile.ProfileCategory
import top.jie65535.mirai.profile.ProfileConfidence
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

    private fun item(content: String, firstSeenAt: Int) = UserProfileItem(
        id = content,
        category = ProfileCategory.NOTABLE_FACT,
        content = content,
        confidence = ProfileConfidence.MEDIUM,
        firstSeenAt = firstSeenAt,
        lastConfirmedAt = firstSeenAt,
    )
}
