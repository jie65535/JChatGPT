package top.jie65535.mirai.profile

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ProfileOperationLoggerTest {
    @Test
    fun formatsOnlyAppliedOperations() {
        val reduction = ProfileReduction(
            profile = UserProfileSnapshot(
                userId = 100,
                groupId = 10,
                summary = "不应打印的摘要",
                cursorTime = 0,
                snapshotEndTime = 0,
            ),
            operations = listOf(
                AppliedProfileOperation(
                    action = ProfileOperationAction.ADD,
                    itemId = "item-1",
                    category = ProfileCategory.INTEREST,
                    content = "关注 Kotlin\n开发",
                    confidence = ProfileConfidence.MEDIUM,
                    relatedUserId = null,
                    evidenceRefs = listOf(1),
                ),
                AppliedProfileOperation(
                    action = ProfileOperationAction.DELETE,
                    itemId = "item-2",
                    category = ProfileCategory.NOTABLE_FACT,
                    content = "已经过期的信息",
                    confidence = ProfileConfidence.LOW,
                    relatedUserId = null,
                    evidenceRefs = listOf(2),
                ),
            ),
        )

        val output = ProfileOperationLogger.format("source=CONVERSATION group=10", listOf(reduction))

        assertContains(checkNotNull(output), "operations=2")
        assertContains(output, "user=100 action=ADD category=interest confidence=medium content=关注 Kotlin 开发")
        assertContains(output, "action=DELETE")
        assertFalse(output.contains("不应打印的摘要"))
        assertFalse(output.contains("item-1"))
        assertFalse(output.contains("evidence"))
    }

    @Test
    fun omitsBatchWhenThereAreNoOperations() {
        val reduction = ProfileReduction(
            profile = UserProfileSnapshot(userId = 100, groupId = 10, cursorTime = 0, snapshotEndTime = 0),
            operations = emptyList(),
        )

        assertNull(ProfileOperationLogger.format("source=BACKFILL", listOf(reduction)))
    }
}
