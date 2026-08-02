package top.jie65535.mirai.profile

import net.mamoe.mirai.message.data.MessageSourceKind
import top.jie65535.mirai.data.ChatMessageRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun rejectsEvidenceAuthoredByAnotherUser() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ConversationProfileReducer.reduce(
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
        }

        assertTrue(failure.message.orEmpty().contains("目标用户"))
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
                                itemId = oldItem.id,
                                confidence = ProfileConfidence.MEDIUM,
                                evidenceRefs = listOf(1),
                            )
                        ),
                        summary = "关注 Kotlin 开发。",
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
        assertTrue(
            ProfilePromptStore.buildConversationUserPrompt(profiles, batch(), USERS)
                .contains("[P:existing-item]")
        )
    }

    @Test
    fun rejectsMoreThanFourOperationsForOneUser() {
        val operations = (1..5).map { index ->
            ProfileModelOperation(
                action = ProfileOperationAction.ADD,
                category = ProfileCategory.NOTABLE_FACT,
                content = "事实 $index",
                confidence = ProfileConfidence.LOW,
                evidenceRefs = listOf(1),
            )
        }

        val failure = assertFailsWith<IllegalArgumentException> {
            ConversationProfileReducer.reduce(
                profiles = USERS.associateWith(::emptyProfile),
                batch = batch(),
                eligibleUserIds = USERS,
                response = ConversationProfileModelResponse(
                    users = listOf(
                        ConversationProfileUserResponse(
                            userAlias = "U1",
                            operations = operations,
                            summary = "包含过多事实。",
                        )
                    )
                ),
                model = "test-model",
                promptVersion = "test-prompt",
                summaryMaxLength = 500,
            )
        }

        assertTrue(failure.message.orEmpty().contains("超过 4 项"))
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
