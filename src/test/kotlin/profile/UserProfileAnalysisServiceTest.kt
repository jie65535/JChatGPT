package top.jie65535.mirai.profile

import kotlinx.coroutines.runBlocking
import net.mamoe.mirai.message.data.MessageSourceKind
import top.jie65535.mirai.data.ChatMessageRecord
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UserProfileAnalysisServiceTest {
    @Test
    fun analyzesAllEligibleUsersWithOneModelCall() = withProfileStore {
        val model = FakeConversationProfileModel { successfulResult() }

        val report = assertNotNull(analyze(batch(), model))

        assertEquals(1, model.calls)
        assertEquals(2, report.analyzedUsers)
        assertEquals(2, report.appliedOperations)
        assertEquals("日常使用 Kotlin 开发", UserProfileStore.load(USER_A)?.items?.single()?.content)
        assertEquals("持续关注本地大模型", UserProfileStore.load(USER_B)?.items?.single()?.content)
        assertEquals(true, UserProfileStore.isConversationProcessed(INPUT_HASH))
    }

    @Test
    fun rejectsWronglyAttributedEvidenceWithoutPartialCommit() = withProfileStore {
        val model = FakeConversationProfileModel {
            result(
                responseFor(
                    alias = "U1",
                    content = "持续关注本地大模型",
                    evidenceRef = 2,
                )
            )
        }

        assertFailsWith<IllegalStateException> {
            analyze(batch(), model, retryMax = 1)
        }

        assertEquals(2, model.calls)
        assertNull(UserProfileStore.load(USER_A))
        assertNull(UserProfileStore.load(USER_B))
        assertFalse(UserProfileStore.isConversationProcessed(INPUT_HASH))
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

    private fun message(ref: Int, fromId: Long, text: String) = ProfilePromptMessage(
        record = ChatMessageRecord(
            botId = BOT,
            fromId = fromId,
            targetId = GROUP,
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

    companion object {
        private const val BOT = 1L
        private const val GROUP = 10L
        private const val USER_A = 100L
        private const val USER_B = 200L
        private const val INPUT_HASH = "service-conversation-hash"
    }
}
