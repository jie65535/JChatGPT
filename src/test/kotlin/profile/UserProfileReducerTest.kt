package top.jie65535.mirai.profile

import net.mamoe.mirai.message.data.MessageSourceKind
import top.jie65535.mirai.data.ChatMessageRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UserProfileReducerTest {
    @Test
    fun addsItemFromTargetAuthoredEvidence() {
        val batch = batchOf(
            message(ref = 1, fromId = TARGET, text = "我平时会写 Kotlin"),
            message(ref = 2, fromId = OTHER, text = "确实"),
        )
        val current = emptyProfile()

        val reduction = UserProfileReducer.reduce(
            current = current,
            batch = batch,
            response = ProfileModelResponse(
                operations = listOf(
                    ProfileModelOperation(
                        action = ProfileOperationAction.ADD,
                        category = ProfileCategory.INTEREST,
                        content = "持续关注 Kotlin 开发",
                        confidence = ProfileConfidence.MEDIUM,
                        evidenceRefs = listOf(1, 2),
                    )
                ),
                summary = "关注 Kotlin 开发。",
            ),
            model = "test-model",
            promptVersion = "test-prompt",
            summaryMaxLength = 500,
        )

        assertEquals(1, reduction.profile.items.size)
        assertEquals("持续关注 Kotlin 开发", reduction.profile.items.single().content)
        assertEquals(batch.endTime, reduction.profile.cursorTime)
        assertEquals("关注 Kotlin 开发。", reduction.profile.summary)
    }

    @Test
    fun rejectsItemSupportedOnlyByAnotherUser() {
        val batch = batchOf(message(ref = 1, fromId = OTHER, text = "我平时会写 Kotlin"))

        val failure = assertFailsWith<IllegalArgumentException> {
            UserProfileReducer.reduce(
                current = emptyProfile(),
                batch = batch,
                response = ProfileModelResponse(
                    operations = listOf(
                        ProfileModelOperation(
                            action = ProfileOperationAction.ADD,
                            category = ProfileCategory.NOTABLE_FACT,
                            content = "从事 Kotlin 开发",
                            confidence = ProfileConfidence.HIGH,
                            evidenceRefs = listOf(1),
                        )
                    ),
                    summary = "从事 Kotlin 开发。",
                ),
                model = "test-model",
                promptVersion = "test-prompt",
                summaryMaxLength = 500,
            )
        }

        assertTrue(failure.message.orEmpty().contains("目标用户"))
    }

    @Test
    fun ignoresSummaryRewriteWhenThereIsNoValidOperation() {
        val batch = batchOf(message(ref = 1, fromId = TARGET, text = "今天天气不错"))
        val current = emptyProfile().copy(summary = "原摘要")

        val reduction = UserProfileReducer.reduce(
            current = current,
            batch = batch,
            response = ProfileModelResponse(summary = "凭空出现的新摘要"),
            model = "test-model",
            promptVersion = "test-prompt",
            summaryMaxLength = 500,
        )

        assertEquals("原摘要", reduction.profile.summary)
        assertEquals(0, reduction.profile.version)
        assertEquals(batch.endTime, reduction.profile.cursorTime)
    }

    @Test
    fun conversationUpdateDoesNotAdvanceHistoricalCursor() {
        val batch = batchOf(message(ref = 1, fromId = TARGET, text = "我平时会写 Kotlin"))
        val current = emptyProfile()

        val reduction = UserProfileReducer.reduce(
            current = current,
            batch = batch,
            response = ProfileModelResponse(
                operations = listOf(
                    ProfileModelOperation(
                        action = ProfileOperationAction.ADD,
                        category = ProfileCategory.INTEREST,
                        content = "持续关注 Kotlin 开发",
                        confidence = ProfileConfidence.MEDIUM,
                        evidenceRefs = listOf(1),
                    )
                ),
                summary = "关注 Kotlin 开发。",
            ),
            model = "test-model",
            promptVersion = "test-prompt",
            summaryMaxLength = 500,
            advanceBackfillCursor = false,
        )

        assertEquals(current.cursorTime, reduction.profile.cursorTime)
        assertEquals(1, reduction.profile.version)
    }

    private fun emptyProfile() = UserProfileSnapshot(
        userId = TARGET,
        cursorTime = 100,
        snapshotEndTime = 1_000,
    )

    private fun batchOf(vararg messages: ProfilePromptMessage) = ProfileHistoryBatch(
        userId = TARGET,
        startTime = 100,
        endTime = 200,
        messages = messages.toList(),
        aliases = mapOf(TARGET to "TARGET", OTHER to "U1"),
        inputHash = "hash",
    )

    private fun message(ref: Int, fromId: Long, text: String) = ProfilePromptMessage(
        record = ChatMessageRecord(
            botId = 1,
            fromId = fromId,
            targetId = 10,
            ids = null,
            internalIds = null,
            time = 120 + ref,
            kind = MessageSourceKind.GROUP,
            code = text,
        ),
        text = text,
        evidenceRef = ref,
        episodeIndex = 1,
    )

    companion object {
        private const val TARGET = 100L
        private const val OTHER = 200L
    }
}
