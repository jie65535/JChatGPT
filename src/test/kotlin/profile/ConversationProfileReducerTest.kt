package top.jie65535.mirai.profile

import net.mamoe.mirai.message.data.MessageSourceKind
import top.jie65535.mirai.data.ChatMessageRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationProfileReducerTest {
    @Test
    fun reducesMultipleUsersFromOneConversation() {
        val batch = batch()
        val profiles = USERS.associateWith(::emptyProfile)

        val reductions = ConversationProfileReducer.reduce(
            profiles = profiles,
            batch = batch,
            eligibleUserIds = USERS,
            response = ConversationProfileModelResponse(
                users = listOf(
                    ConversationProfileUserResponse(
                        userAlias = "U1",
                        operations = listOf(
                            ProfileModelOperation(
                                action = ProfileOperationAction.ADD,
                                category = ProfileCategory.NOTABLE_FACT,
                                content = "日常使用 Kotlin 开发",
                                confidence = ProfileConfidence.MEDIUM,
                                evidenceRefs = listOf(1),
                            )
                        ),
                        summary = "日常使用 Kotlin 开发。",
                    ),
                    ConversationProfileUserResponse(
                        userAlias = "U2",
                        operations = listOf(
                            ProfileModelOperation(
                                action = ProfileOperationAction.ADD,
                                category = ProfileCategory.INTEREST,
                                content = "关注本地大模型",
                                confidence = ProfileConfidence.MEDIUM,
                                evidenceRefs = listOf(2),
                            )
                        ),
                        summary = "关注本地大模型。",
                    ),
                )
            ),
            model = "test-model",
            promptVersion = "test-prompt",
            summaryMaxLength = 500,
        )

        assertEquals(2, reductions.size)
        assertEquals("日常使用 Kotlin 开发", reductions.single { it.profile.userId == USER_A }.profile.items.single().content)
        assertEquals("关注本地大模型", reductions.single { it.profile.userId == USER_B }.profile.items.single().content)
        assertTrue(reductions.all { it.profile.cursorTime == 0 })
    }

    @Test
    fun skipsEvidenceAuthoredByAnotherUser() {
        val reductions = ConversationProfileReducer.reduce(
            profiles = USERS.associateWith(::emptyProfile),
            batch = batch(),
            eligibleUserIds = USERS,
            response = ConversationProfileModelResponse(
                users = listOf(
                    ConversationProfileUserResponse(
                        userAlias = "U1",
                        operations = listOf(
                            ProfileModelOperation(
                                action = ProfileOperationAction.ADD,
                                category = ProfileCategory.NOTABLE_FACT,
                                content = "关注本地大模型",
                                confidence = ProfileConfidence.MEDIUM,
                                evidenceRefs = listOf(2),
                            )
                        ),
                        summary = "关注本地大模型。",
                    )
                )
            ),
            model = "test-model",
            promptVersion = "test-prompt",
            summaryMaxLength = 500,
        )

        val userReduction = reductions.single { it.profile.userId == USER_A }
        assertTrue(userReduction.operations.isEmpty())
        assertTrue(userReduction.skippedOperations.single().contains("目标用户"))
    }

    @Test
    fun confirmsAnExistingItemInsteadOfCreatingAnIndependentProfile() {
        val oldItem = UserProfileItem(
            id = "existing-item",
            category = ProfileCategory.INTEREST,
            content = "关注 Kotlin 开发",
            confidence = ProfileConfidence.LOW,
            firstSeenAt = 80,
            lastConfirmedAt = 80,
        )
        val existing = emptyProfile(USER_A).copy(
            summary = "关注 Kotlin 开发。",
            version = 1,
            reliable = true,
            items = listOf(oldItem),
        )
        val profiles = mapOf(USER_A to existing, USER_B to emptyProfile(USER_B))

        val reductions = ConversationProfileReducer.reduce(
            profiles = profiles,
            batch = batch(),
            eligibleUserIds = USERS,
            response = ConversationProfileModelResponse(
                users = listOf(
                    ConversationProfileUserResponse(
                        userAlias = "U1",
                        operations = listOf(
                            ProfileModelOperation(
                                action = ProfileOperationAction.CONFIRM,
                                itemRef = "P1",
                                confidence = ProfileConfidence.MEDIUM,
                                evidenceRefs = listOf(1),
                            )
                        ),
                        summary = "本批再次提到 Kotlin。",
                    )
                )
            ),
            model = "test-model",
            promptVersion = "test-prompt",
            summaryMaxLength = 500,
        )

        val updated = reductions.single { it.profile.userId == USER_A }.profile
        assertEquals(2, updated.version)
        assertEquals("existing-item", updated.items.single().id)
        assertEquals(ProfileConfidence.MEDIUM, updated.items.single().confidence)
        assertEquals(existing.summary, updated.summary)
        val prompt = ProfilePromptStore.buildConversationUserPrompt(
            profiles,
            batch(),
            USERS,
            mapOf(USER_A to mapOf(oldItem.id to ProfileItemSupportStats(4, 100, 400))),
        )
        assertTrue(prompt.contains("[P1]"))
        assertTrue(prompt.contains("supports=4"))
        assertTrue(prompt.contains("support_range="))
        assertFalse(prompt.contains(oldItem.id))
    }

    @Test
    fun rebasesItemReferenceByStableIdWhenConcurrentUpdateChangesItemOrder() {
        val target = item("target", "原始目标条目", 100)
        val expected = emptyProfile(USER_A).copy(version = 1, items = listOf(target))
        val inserted = item("inserted", "并发新增条目", 50)
        val latest = expected.copy(version = 2, items = listOf(inserted, target))

        val reduction = ConversationProfileReducer.reduceRebased(
            expectedProfiles = mapOf(USER_A to expected, USER_B to emptyProfile(USER_B)),
            latestProfiles = mapOf(USER_A to latest, USER_B to emptyProfile(USER_B)),
            batch = batch(),
            eligibleUserIds = USERS,
            response = ConversationProfileModelResponse(
                users = listOf(
                    ConversationProfileUserResponse(
                        userAlias = "U1",
                        operations = listOf(
                            ProfileModelOperation(
                                action = ProfileOperationAction.UPDATE,
                                itemRef = "P1",
                                content = "更新后的目标条目",
                                evidenceRefs = listOf(1),
                            )
                        ),
                    )
                )
            ),
            model = "test-model",
            promptVersion = "test-prompt",
            summaryMaxLength = 500,
        ).single { it.profile.userId == USER_A }

        assertEquals("并发新增条目", reduction.profile.items.single { it.id == "inserted" }.content)
        assertEquals("更新后的目标条目", reduction.profile.items.single { it.id == "target" }.content)
        assertEquals(1, reduction.operations.size)
    }

    @Test
    fun skipsRebasedOperationWhenConcurrentUpdateRemovedTargetItem() {
        val target = item("target", "稍后被删除的条目", 100)
        val expected = emptyProfile(USER_A).copy(version = 1, items = listOf(target))
        val latest = expected.copy(version = 2, items = emptyList())

        val reduction = ConversationProfileReducer.reduceRebased(
            expectedProfiles = mapOf(USER_A to expected, USER_B to emptyProfile(USER_B)),
            latestProfiles = mapOf(USER_A to latest, USER_B to emptyProfile(USER_B)),
            batch = batch(),
            eligibleUserIds = USERS,
            response = ConversationProfileModelResponse(
                users = listOf(
                    ConversationProfileUserResponse(
                        userAlias = "U1",
                        operations = listOf(
                            ProfileModelOperation(
                                action = ProfileOperationAction.DELETE,
                                itemRef = "P1",
                                evidenceRefs = listOf(1),
                            )
                        ),
                    )
                )
            ),
            model = "test-model",
            promptVersion = "test-prompt",
            summaryMaxLength = 500,
        ).single { it.profile.userId == USER_A }

        assertTrue(reduction.operations.isEmpty())
        assertTrue(reduction.skippedOperations.single().contains("不存在"))
        assertTrue(reduction.profile.items.isEmpty())
    }

    @Test
    fun acceptsMoreThanFourOperationsForOneUser() {
        val operations = (1..5).map { index ->
            ProfileModelOperation(
                action = ProfileOperationAction.ADD,
                category = ProfileCategory.NOTABLE_FACT,
                content = "事实 $index",
                confidence = ProfileConfidence.LOW,
                evidenceRefs = listOf(1),
            )
        }

        val reductions = ConversationProfileReducer.reduce(
            profiles = USERS.associateWith(::emptyProfile),
            batch = batch(),
            eligibleUserIds = USERS,
            response = ConversationProfileModelResponse(
                users = listOf(
                    ConversationProfileUserResponse(
                        userAlias = "U1",
                        operations = operations,
                        summary = "包含多项事实。",
                    )
                )
            ),
            model = "test-model",
            promptVersion = "test-prompt",
            summaryMaxLength = 500,
        )

        assertEquals(5, reductions.single { it.profile.userId == USER_A }.operations.size)
    }

    @Test
    fun mergesDuplicateUserResultGroupsAndDeduplicatesOperations() {
        val operation = ProfileModelOperation(
            action = ProfileOperationAction.ADD,
            category = ProfileCategory.NOTABLE_FACT,
            content = "日常使用 Kotlin 开发",
            confidence = ProfileConfidence.MEDIUM,
            evidenceRefs = listOf(1),
        )

        val reductions = ConversationProfileReducer.reduce(
            profiles = USERS.associateWith(::emptyProfile),
            batch = batch(),
            eligibleUserIds = USERS,
            response = ConversationProfileModelResponse(
                users = listOf(
                    ConversationProfileUserResponse(
                        userAlias = "U1",
                        operations = listOf(operation),
                        summary = "使用 Kotlin。",
                    ),
                    ConversationProfileUserResponse(
                        userAlias = "U1",
                        operations = listOf(
                            operation,
                            ProfileModelOperation(
                                action = ProfileOperationAction.ADD,
                                category = ProfileCategory.PREFERENCE,
                                content = "偏好 Kotlin",
                                confidence = ProfileConfidence.LOW,
                                evidenceRefs = listOf(1),
                            ),
                        ),
                        summary = "使用并偏好 Kotlin。",
                    ),
                )
            ),
            model = "test-model",
            promptVersion = "test-prompt",
            summaryMaxLength = 500,
        )

        val reduction = reductions.single { it.profile.userId == USER_A }
        assertEquals(2, reduction.operations.size)
        assertEquals(2, reduction.profile.items.size)
        assertEquals("使用并偏好 Kotlin。", reduction.profile.summary)
    }

    @Test
    fun ignoresModelResultsForNonCandidateContextUsers() {
        val contextUser = 400L
        val batch = batch().copy(aliases = batch().aliases + (contextUser to "U4"))
        val reductions = ConversationProfileReducer.reduce(
            profiles = USERS.associateWith(::emptyProfile),
            batch = batch,
            eligibleUserIds = USERS,
            response = ConversationProfileModelResponse(
                users = listOf(
                    ConversationProfileUserResponse(
                        userAlias = "U4",
                        operations = listOf(
                            ProfileModelOperation(
                                action = ProfileOperationAction.ADD,
                                category = ProfileCategory.INTEREST,
                                content = "关注 Kotlin",
                                confidence = ProfileConfidence.LOW,
                                evidenceRefs = listOf(1),
                            )
                        ),
                        summary = "关注 Kotlin。",
                    ),
                    ConversationProfileUserResponse(
                        userAlias = "U1",
                        operations = listOf(
                            ProfileModelOperation(
                                action = ProfileOperationAction.ADD,
                                category = ProfileCategory.NOTABLE_FACT,
                                content = "日常使用 Kotlin 开发",
                                confidence = ProfileConfidence.MEDIUM,
                                evidenceRefs = listOf(1),
                            )
                        ),
                        summary = "日常使用 Kotlin 开发。",
                    ),
                )
            ),
            model = "test-model",
            promptVersion = "test-prompt",
            summaryMaxLength = 500,
        )

        assertEquals(1, reductions.single { it.profile.userId == USER_A }.operations.size)
        assertTrue(reductions.single { it.profile.userId == USER_B }.operations.isEmpty())
        val prompt = ProfilePromptStore.buildConversationUserPrompt(
            profiles = USERS.associateWith(::emptyProfile),
            batch = batch,
            eligibleUserIds = USERS,
        )
        assertTrue(prompt.contains("候选用户别名: U1, U2"))
    }

    private fun batch() = ConversationProfileBatch(
        botId = BOT,
        groupId = 10,
        startTime = 100,
        endTime = 200,
        messages = listOf(
            message(1, USER_A, "我平时用 Kotlin 写项目"),
            message(2, USER_B, "我最近一直在研究本地大模型"),
        ),
        aliases = mapOf(BOT to "BOT", USER_A to "U1", USER_B to "U2"),
        inputHash = "conversation-hash",
    )

    private fun emptyProfile(userId: Long) = UserProfileSnapshot(
        userId = userId,
        cursorTime = 0,
        snapshotEndTime = 0,
    )

    private fun item(id: String, content: String, firstSeenAt: Int) = UserProfileItem(
        id = id,
        category = ProfileCategory.NOTABLE_FACT,
        content = content,
        confidence = ProfileConfidence.MEDIUM,
        firstSeenAt = firstSeenAt,
        lastConfirmedAt = firstSeenAt,
    )

    private fun message(ref: Int, fromId: Long, text: String) = ProfilePromptMessage(
        record = ChatMessageRecord(
            botId = BOT,
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
        private const val BOT = 1L
        private const val USER_A = 100L
        private const val USER_B = 200L
        private val USERS = setOf(USER_A, USER_B)
    }
}
