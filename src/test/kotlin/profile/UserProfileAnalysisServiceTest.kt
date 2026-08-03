package top.jie65535.mirai.profile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.mamoe.mirai.message.data.MessageSourceKind
import top.jie65535.mirai.data.ChatMessageRecord
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserProfileAnalysisServiceTest {
    @Test
    fun onlySchedulesGroupsWhoseCursorDoesNotCoverLatestHistory() {
        val bounds = ProfileHistoryReader.GroupTimeBounds(
            botId = BOT,
            groupId = GROUP,
            startTime = 100,
            endTime = 500,
        )

        assertTrue(isGroupAnalysisPending(bounds, null))
        assertTrue(
            isGroupAnalysisPending(
                bounds,
                GroupProfileCursor(BOT, GROUP, cursorTime = 300, snapshotEndTime = 500),
            )
        )
        assertTrue(
            isGroupAnalysisPending(
                bounds,
                GroupProfileCursor(BOT, GROUP, cursorTime = 400, snapshotEndTime = 400),
            )
        )
        assertFalse(
            isGroupAnalysisPending(
                bounds,
                GroupProfileCursor(BOT, GROUP, cursorTime = 500, snapshotEndTime = 500),
            )
        )
    }

    @Test
    fun analyzesAllEligibleUsersWithOneModelCall() = withProfileStore {
        val model = FakeConversationProfileModel { successfulResult() }

        val report = assertNotNull(analyze(batch(), model))

        assertEquals(1, model.calls)
        assertEquals(2, report.analyzedUsers)
        assertEquals(2, report.appliedOperations)
        assertEquals(0, report.skippedOperations)
        assertEquals("日常使用 Kotlin 开发", UserProfileStore.load(USER_A)?.items?.single()?.content)
        assertEquals("持续关注本地大模型", UserProfileStore.load(USER_B)?.items?.single()?.content)
        assertEquals(true, UserProfileStore.isConversationProcessed(INPUT_HASH))
    }

    @Test
    fun skipsWronglyAttributedEvidenceAndMarksConversationProcessed() = withProfileStore {
        val model = FakeConversationProfileModel {
            result(
                responseFor(
                    alias = "U1",
                    content = "持续关注本地大模型",
                    evidenceRef = 2,
                )
            )
        }

        val report = assertNotNull(analyze(batch(), model, retryMax = 1))

        assertEquals(1, model.calls)
        assertEquals(0, report.appliedOperations)
        assertEquals(1, report.skippedOperations)
        assertNotNull(UserProfileStore.load(USER_A))
        assertNotNull(UserProfileStore.load(USER_B))
        assertTrue(UserProfileStore.isConversationProcessed(INPUT_HASH))
    }

    @Test
    fun modelFailureDoesNotCommitOrMarkConversationProcessed() = withProfileStore {
        val model = FakeConversationProfileModel { throw IOException("model unavailable") }

        assertFailsWith<IllegalStateException> {
            analyze(batch(), model, retryMax = 1)
        }

        assertEquals(2, model.calls)
        assertNull(UserProfileStore.load(USER_A))
        assertNull(UserProfileStore.load(USER_B))
        assertFalse(UserProfileStore.isConversationProcessed(INPUT_HASH))
    }

    @Test
    fun skipsConversationAlreadyProcessedByAnEarlierCall() = withProfileStore {
        val model = FakeConversationProfileModel { successfulResult() }

        assertNotNull(analyze(batch(), model))
        assertNull(analyze(batch(), model))

        assertEquals(1, model.calls)
    }

    @Test
    fun skipsModelWhenNoParticipantMeetsTheTextThreshold() = withProfileStore {
        val model = FakeConversationProfileModel {
            throw AssertionError("model must not be called")
        }

        val report = analyze(batch(), model, minAuthoredTextChars = 1_000)

        assertNull(report)
        assertEquals(0, model.calls)
        assertFalse(UserProfileStore.isConversationProcessed(INPUT_HASH))
    }

    @Test
    fun analyzesDisjointGroupsConcurrently() = withProfileStore {
        val entered = AtomicInteger()
        val bothEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fun concurrentModel(content: String) = InspectingConversationProfileModel {
            if (entered.incrementAndGet() == 2) bothEntered.complete(Unit)
            release.await()
            result(responseFor("U1", content, evidenceRef = 1))
        }

        coroutineScope {
            val first = async {
                analyze(singleUserBatch(USER_A, 10, "parallel-a"), concurrentModel("用户 A 的信息"))
            }
            val second = async {
                analyze(singleUserBatch(USER_B, 20, "parallel-b"), concurrentModel("用户 B 的信息"))
            }
            withTimeout(1_000) { bothEntered.await() }
            release.complete(Unit)
            assertNotNull(first.await())
            assertNotNull(second.await())
        }
    }

    @Test
    fun sharedUserRequestsRunConcurrentlyAndConflictReloadsLatestProfile() = withProfileStore {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondFirstEntered = CompletableDeferred<Unit>()
        val releaseSecondFirst = CompletableDeferred<Unit>()
        val secondRetried = CompletableDeferred<Unit>()
        val secondCalls = AtomicInteger()
        val firstModel = InspectingConversationProfileModel { profiles ->
            assertEquals(0, profiles.getValue(USER_A).version)
            firstEntered.complete(Unit)
            releaseFirst.await()
            result(responseFor("U1", "第一群归纳的信息", evidenceRef = 1))
        }
        val secondModel = InspectingConversationProfileModel { profiles ->
            when (secondCalls.incrementAndGet()) {
                1 -> {
                    assertEquals(0, profiles.getValue(USER_A).version)
                    secondFirstEntered.complete(Unit)
                    releaseSecondFirst.await()
                }

                2 -> {
                    assertEquals(1, profiles.getValue(USER_A).version)
                    secondRetried.complete(Unit)
                }

                else -> error("共享用户冲突只应触发一次重算")
            }
            result(responseFor("U1", "第二群归纳的信息", evidenceRef = 1))
        }

        coroutineScope {
            val first = async { analyze(singleUserBatch(USER_A, 10, "shared-a"), firstModel) }
            firstEntered.await()
            val second = async { analyze(singleUserBatch(USER_A, 20, "shared-b"), secondModel) }
            withTimeout(1_000) { secondFirstEntered.await() }
            releaseFirst.complete(Unit)
            assertNotNull(first.await())
            releaseSecondFirst.complete(Unit)
            withTimeout(1_000) { secondRetried.await() }
            assertNotNull(second.await())
        }

        assertEquals(2, secondCalls.get())
        val profile = assertNotNull(UserProfileStore.load(USER_A))
        assertEquals(2, profile.version)
        assertEquals(2, profile.items.size)
    }

    private suspend fun analyze(
        batch: ConversationProfileBatch,
        model: ConversationProfileModel,
        minAuthoredTextChars: Int = 1,
        retryMax: Int = 0,
    ): ConversationProfileAnalysisReport? = UserProfileAnalysisService.analyzeConversationBatch(
        batch = batch,
        minAuthoredTextChars = minAuthoredTextChars,
        model = model,
        retryMax = retryMax,
        summaryMaxLength = 500,
    )

    private fun successfulResult() = result(
        responseFor("U1", "日常使用 Kotlin 开发", evidenceRef = 1),
        responseFor("U2", "持续关注本地大模型", evidenceRef = 2),
    )

    private fun result(
        vararg users: ConversationProfileUserResponse,
    ) = ConversationProfileModelResult(
        response = ConversationProfileModelResponse(users.toList()),
        rawResponse = "test-response",
        usage = ProfileTokenUsage(promptTokens = 100, completionTokens = 20, cachedTokens = 40),
    )

    private fun responseFor(
        alias: String,
        content: String,
        evidenceRef: Int,
    ) = ConversationProfileUserResponse(
        userAlias = alias,
        operations = listOf(
            ProfileModelOperation(
                action = ProfileOperationAction.ADD,
                category = ProfileCategory.NOTABLE_FACT,
                content = content,
                confidence = ProfileConfidence.MEDIUM,
                evidenceRefs = listOf(evidenceRef),
            )
        ),
        summary = "$content。",
    )

    private fun batch() = ConversationProfileBatch(
        botId = BOT,
        groupId = GROUP,
        startTime = 100,
        endTime = 200,
        messages = listOf(
            message(1, USER_A, "我日常使用 Kotlin 开发"),
            message(2, USER_B, "我持续关注本地大模型"),
        ),
        aliases = mapOf(BOT to "BOT", USER_A to "U1", USER_B to "U2"),
        inputHash = INPUT_HASH,
    )

    private fun singleUserBatch(
        userId: Long,
        groupId: Long,
        inputHash: String,
    ) = ConversationProfileBatch(
        botId = BOT,
        groupId = groupId,
        startTime = 100,
        endTime = 200,
        messages = listOf(message(1, userId, "用于并发画像测试的信息", groupId)),
        aliases = mapOf(BOT to "BOT", userId to "U1"),
        inputHash = inputHash,
    )

    private fun message(ref: Int, fromId: Long, text: String, groupId: Long = GROUP) = ProfilePromptMessage(
        record = ChatMessageRecord(
            botId = BOT,
            fromId = fromId,
            targetId = groupId,
            ids = null,
            internalIds = null,
            time = 120 + ref,
            kind = MessageSourceKind.GROUP,
            code = """[{"type":"PlainText","content":"$text"}]""",
        ),
        text = text,
        evidenceRef = ref,
        episodeIndex = 1,
    )

    private fun withProfileStore(block: suspend () -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("jchatgpt-profile-service-test-")
        try {
            UserProfileStore.init(directory.toFile())
            block()
        } finally {
            UserProfileStore.close()
            directory.toFile().deleteRecursively()
        }
    }

    private class FakeConversationProfileModel(
        private val behavior: suspend () -> ConversationProfileModelResult,
    ) : ConversationProfileModel {
        var calls: Int = 0
            private set

        override val modelName: String = "fake-profile-model"

        override suspend fun analyzeConversation(
            profiles: Map<Long, UserProfileSnapshot>,
            batch: ConversationProfileBatch,
            eligibleUserIds: Set<Long>,
        ): ConversationProfileModelResult {
            calls++
            return behavior()
        }
    }

    private class InspectingConversationProfileModel(
        private val behavior: suspend (Map<Long, UserProfileSnapshot>) -> ConversationProfileModelResult,
    ) : ConversationProfileModel {
        override val modelName: String = "inspecting-profile-model"

        override suspend fun analyzeConversation(
            profiles: Map<Long, UserProfileSnapshot>,
            batch: ConversationProfileBatch,
            eligibleUserIds: Set<Long>,
        ): ConversationProfileModelResult = behavior(profiles)
    }

    companion object {
        private const val BOT = 1L
        private const val GROUP = 10L
        private const val USER_A = 100L
        private const val USER_B = 200L
        private const val INPUT_HASH = "service-conversation-hash"
    }
}
