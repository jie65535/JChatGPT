package top.jie65535.mirai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import top.jie65535.mirai.profile.ProfileCategory
import top.jie65535.mirai.profile.ProfileConfidence
import top.jie65535.mirai.profile.ProfileItemSupportStats
import top.jie65535.mirai.profile.UserProfileItem
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueryUserProfileAgentTest {
    @Test
    fun exposesNoTargetParametersToTheModel() {
        val function = QueryUserProfileAgent().tool.function
        val schema = requireNotNull(function.parameters).schema.jsonObject

        assertEquals("查询本轮触发消息发送者的完整用户画像及公开资料卡。", function.description)
        assertTrue(schema.getValue("properties").jsonObject.isEmpty())
    }

    @Test
    fun onlyAllowsTheTriggeringUsersProfile() {
        val senderId = 100L

        assertTrue(isOwnProfileQuery(null, senderId, requestedUserId = null))
        assertTrue(
            isOwnProfileQuery(
                buildJsonObject { put("includeItems", false) },
                senderId,
                requestedUserId = null,
            )
        )
        assertTrue(
            isOwnProfileQuery(
                buildJsonObject { put("userId", senderId) },
                senderId,
                requestedUserId = senderId,
            )
        )
        assertFalse(
            isOwnProfileQuery(
                buildJsonObject { put("userId", 200L) },
                senderId,
                requestedUserId = 200L,
            )
        )
        assertFalse(
            isOwnProfileQuery(
                buildJsonObject { put("name", "小李") },
                senderId,
                requestedUserId = null,
            )
        )
        assertFalse(
            isOwnProfileQuery(
                buildJsonObject { put("qq", 200L) },
                senderId,
                requestedUserId = null,
            )
        )
        assertEquals("出于隐私考虑，禁止查询他人画像详情", PROFILE_QUERY_PRIVACY_REFUSAL)
    }

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

        val expectedConfirmationDate = Instant.ofEpochSecond(1_751_472_000)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        assertEquals(
            "从事 Java 底层开发〔事实，高置信；4次支持；最近确认于 $expectedConfirmationDate〕",
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
