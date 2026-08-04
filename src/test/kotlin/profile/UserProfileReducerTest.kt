package top.jie65535.mirai.profile

import net.mamoe.mirai.message.data.MessageSourceKind
import top.jie65535.mirai.data.ChatMessageRecord
import top.jie65535.mirai.data.ContactGroupMemberHint
import top.jie65535.mirai.data.ContactProfileHint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun skipsItemSupportedOnlyByAnotherUser() {
        val batch = batchOf(message(ref = 1, fromId = OTHER, text = "我平时会写 Kotlin"))

        val reduction = UserProfileReducer.reduce(
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

        assertTrue(reduction.operations.isEmpty())
        assertTrue(reduction.profile.items.isEmpty())
        assertTrue(reduction.skippedOperations.single().contains("目标用户"))
        assertEquals(batch.endTime, reduction.profile.cursorTime)
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

    @Test
    fun resolvesPromptLocalItemReferenceWithoutExposingStoredId() {
        val item = existingItem()
        val current = emptyProfile().copy(items = listOf(item), reliable = true)
        val batch = batchOf(message(ref = 1, fromId = TARGET, text = "我现在仍然关注 Kotlin"))

        val reduction = UserProfileReducer.reduce(
            current = current,
            batch = batch,
            response = ProfileModelResponse(
                operations = listOf(
                    ProfileModelOperation(
                        action = ProfileOperationAction.CONFIRM,
                        itemRef = "P1",
                        confidence = ProfileConfidence.HIGH,
                        evidenceRefs = listOf(1),
                    )
                ),
                summary = "TARGET 仍然关注 Kotlin。",
            ),
            model = "test-model",
            promptVersion = "test-prompt",
            summaryMaxLength = 500,
        )

        assertEquals(item.id, reduction.profile.items.single().id)
        assertEquals(ProfileConfidence.HIGH, reduction.profile.items.single().confidence)
        assertEquals("该用户仍然关注 Kotlin。", reduction.profile.summary)
        val prompt = ProfilePromptStore.buildUserPrompt(current, batch)
        assertTrue(prompt.contains("[P1]"))
        assertFalse(prompt.contains(item.id))
    }

    @Test
    fun rendersContactSnapshotAsNonEvidenceAndIncludesItemCount() {
        val profile = emptyProfile().copy(items = listOf(existingItem()))
        val batch = batchOf(message(ref = 1, fromId = TARGET, text = "我平时会写 Kotlin")).copy(
            contactHints = mapOf(
                TARGET to ContactProfileHint(
                    userId = TARGET,
                    nickname = "公开昵称",
                    sign = "公开签名",
                    memberships = listOf(
                        ContactGroupMemberHint(
                            groupId = 10,
                            groupName = "测试群",
                            nameCard = "群名片",
                            role = "admin",
                        )
                    ),
                )
            ),
        )

        val prompt = ProfilePromptStore.buildUserPrompt(profile, batch)

        assertTrue(prompt.contains("当前条目数: 1"))
        assertTrue(prompt.contains("联系人快照（辅助识别，不是画像证据）"))
        assertTrue(prompt.contains("昵称=公开昵称"))
        assertTrue(prompt.contains("群名片=群名片"))
        assertTrue(ProfilePromptStore.systemPrompt.contains("不得仅凭昵称、群名片"))
    }

    @Test
    fun keepsComprehensiveSummaryWhenBatchOnlyConfirmsOneItem() {
        val interest = existingItem()
        val work = UserProfileItem(
            id = "work-item",
            category = ProfileCategory.NOTABLE_FACT,
            content = "从事上位机开发",
            confidence = ProfileConfidence.HIGH,
            firstSeenAt = 70,
            lastConfirmedAt = 70,
        )
        val current = emptyProfile().copy(
            summary = "从事上位机开发，并持续关注 Kotlin 生态。",
            reliable = true,
            items = listOf(interest, work),
        )
        val batch = batchOf(message(ref = 1, fromId = TARGET, text = "我还在关注 Kotlin"))

        val reduction = UserProfileReducer.reduce(
            current = current,
            batch = batch,
            response = ProfileModelResponse(
                operations = listOf(
                    ProfileModelOperation(
                        action = ProfileOperationAction.CONFIRM,
                        itemRef = "P1",
                        evidenceRefs = listOf(1),
                    )
                ),
                summary = "仍在关注 Kotlin。",
            ),
            model = "test-model",
            promptVersion = "test-prompt",
            summaryMaxLength = 500,
        )

        assertEquals(current.summary, reduction.profile.summary)
    }

    @Test
    fun skipsUnknownPromptLocalItemReference() {
        val current = emptyProfile().copy(items = listOf(existingItem()), reliable = true)
        val batch = batchOf(message(ref = 1, fromId = TARGET, text = "我仍然关注 Kotlin"))

        val reduction = UserProfileReducer.reduce(
            current = current,
            batch = batch,
            response = ProfileModelResponse(
                operations = listOf(
                    ProfileModelOperation(
                        action = ProfileOperationAction.CONFIRM,
                        itemRef = "P2",
                        evidenceRefs = listOf(1),
                    )
                ),
                summary = "关注 Kotlin。",
            ),
            model = "test-model",
            promptVersion = "test-prompt",
            summaryMaxLength = 500,
        )

        assertEquals(current.items, reduction.profile.items)
        assertTrue(reduction.operations.isEmpty())
        assertTrue(reduction.skippedOperations.single().contains("P2"))
    }

    @Test
    fun appliesSafeOperationAndSkipsInvalidEvidenceWithoutRewritingSummary() {
        val current = emptyProfile().copy(summary = "原摘要")
        val batch = batchOf(message(ref = 1, fromId = TARGET, text = "我平时会写 Kotlin"))

        val reduction = UserProfileReducer.reduce(
            current = current,
            batch = batch,
            response = ProfileModelResponse(
                operations = listOf(
                    ProfileModelOperation(
                        action = ProfileOperationAction.ADD,
                        category = ProfileCategory.NOTABLE_FACT,
                        content = "日常使用 Kotlin 开发",
                        confidence = ProfileConfidence.MEDIUM,
                        evidenceRefs = listOf(1),
                    ),
                    ProfileModelOperation(
                        action = ProfileOperationAction.ADD,
                        category = ProfileCategory.INTEREST,
                        content = "关注本地大模型",
                        confidence = ProfileConfidence.LOW,
                        evidenceRefs = listOf(323),
                    ),
                ),
                summary = "使用 Kotlin 并关注本地大模型。",
            ),
            model = "test-model",
            promptVersion = "test-prompt",
            summaryMaxLength = 500,
        )

        assertEquals(1, reduction.operations.size)
        assertEquals("日常使用 Kotlin 开发", reduction.profile.items.single().content)
        assertTrue(reduction.skippedOperations.single().contains("e:323"))
        assertEquals("原摘要", reduction.profile.summary)
    }

    @Test
    fun rejectsBatchScopedPersistentText() {
        val current = emptyProfile().copy(summary = "原摘要")
        val batch = batchOf(message(ref = 1, fromId = TARGET, text = "喵喵喵"))

        val reduction = UserProfileReducer.reduce(
            current = current,
            batch = batch,
            response = ProfileModelResponse(
                operations = listOf(
                    ProfileModelOperation(
                        action = ProfileOperationAction.ADD,
                        category = ProfileCategory.EXPRESSION_STYLE,
                        content = "本批多次使用拟猫化措辞",
                        confidence = ProfileConfidence.LOW,
                        evidenceRefs = listOf(1),
                    )
                ),
                summary = "本批主要使用拟猫化措辞。",
            ),
            model = "test-model",
            promptVersion = "test-prompt",
            summaryMaxLength = 500,
        )

        assertTrue(reduction.operations.isEmpty())
        assertTrue(reduction.skippedOperations.single().contains("批次化"))
        assertEquals("原摘要", reduction.profile.summary)
    }

    @Test
    fun keepsEvidenceRangeMonotonicWhenOlderBatchUpdatesExistingItem() {
        val old = existingItem().copy(
            firstSeenAt = 150,
            lastConfirmedAt = 180,
        )
        val current = emptyProfile().copy(items = listOf(old), reliable = true)
        val batch = batchOf(message(ref = 1, fromId = TARGET, text = "我更早也关注 Kotlin"))

        val reduction = UserProfileReducer.reduce(
            current = current,
            batch = batch,
            response = ProfileModelResponse(
                operations = listOf(
                    ProfileModelOperation(
                        action = ProfileOperationAction.UPDATE,
                        itemRef = "P1",
                        content = "关注 Kotlin 开发",
                        evidenceRefs = listOf(1),
                    )
                ),
                summary = "关注 Kotlin 开发。",
            ),
            model = "test-model",
            promptVersion = "test-prompt",
            summaryMaxLength = 500,
        )

        val updated = reduction.profile.items.single()
        assertEquals(121, updated.firstSeenAt)
        assertEquals(180, updated.lastConfirmedAt)
    }

    @Test
    fun removesTemporaryAliasesFromPersistedRelationshipText() {
        val batch = batchOf(message(ref = 1, fromId = TARGET, text = "先找到下家再离职"))

        val reduction = UserProfileReducer.reduce(
            current = emptyProfile(),
            batch = batch,
            response = ProfileModelResponse(
                operations = listOf(
                    ProfileModelOperation(
                        action = ProfileOperationAction.ADD,
                        category = ProfileCategory.RELATIONSHIP_NOTE,
                        content = "TARGET 在 U1 提及离职时会建议 U1 先找到下家",
                        confidence = ProfileConfidence.LOW,
                        relatedUserAlias = "U1",
                        evidenceRefs = listOf(1),
                    )
                ),
                summary = "TARGET 会给 U1 提供务实建议。",
            ),
            model = "test-model",
            promptVersion = "test-prompt",
            summaryMaxLength = 500,
        )

        assertEquals("本人在对方提及离职时会建议对方先找到下家", reduction.profile.items.single().content)
        assertEquals("该用户会给其他用户提供务实建议。", reduction.profile.summary)
        assertFalse(reduction.profile.items.single().content.contains("U1"))
    }

    private fun emptyProfile() = UserProfileSnapshot(
        userId = TARGET,
        cursorTime = 100,
        snapshotEndTime = 1_000,
    )

    private fun existingItem() = UserProfileItem(
        id = "b287070d-b7c0-4d50-a18c-fa8348932048",
        category = ProfileCategory.INTEREST,
        content = "关注 Kotlin 开发",
        confidence = ProfileConfidence.MEDIUM,
        firstSeenAt = 80,
        lastConfirmedAt = 80,
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
