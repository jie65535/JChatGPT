package top.jie65535.mirai.conversation

import net.mamoe.mirai.message.data.MessageSourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationRuntimeStateTest {
    @Test
    fun keepsFirstPendingTriggerUntilTheRunningLoopConsumesIt() {
        val state = ConversationRuntimeState<String>()
        val running = assertIs<ConversationRuntimeState.BeginResult.Started<String>>(
            state.beginExplicit(KEY, "first")
        ).running

        assertEquals(
            ConversationRuntimeState.BeginResult.Queued(newlyQueued = true),
            state.beginExplicit(KEY, "second"),
        )
        assertEquals(
            ConversationRuntimeState.BeginResult.Queued(newlyQueued = false),
            state.beginExplicit(KEY, "third"),
        )
        assertEquals("second", state.takePending(running))
        assertNull(state.takePending(running))
    }

    @Test
    fun pendingTriggerWinsOverWaitAtTerminalSettlement() {
        val state = ConversationRuntimeState<String>()
        val running = assertIs<ConversationRuntimeState.BeginResult.Started<String>>(
            state.beginExplicit(KEY, "first")
        ).running
        state.beginExplicit(KEY, "second")

        val finish = state.finish(
            running = running,
            waitDirective = WAIT,
            nowEpochSecond = 100,
            allowPendingContinuation = true,
            onFinished = { error("continuing must keep the runtime resources active") },
        )

        assertEquals("second", assertIs<ConversationRuntimeState.FinishResult.Continue<String>>(finish).event)
        assertFalse(state.isExpectedUser(KEY, TARGET_USER, nowEpochSecond = 101))
    }

    @Test
    fun expectedUserAtomicallyConsumesObservationAndRestoresWaitContext() {
        val state = ConversationRuntimeState<String>()
        val running = assertIs<ConversationRuntimeState.BeginResult.Started<String>>(
            state.beginExplicit(KEY, "first")
        ).running
        var released = false
        val observation = assertIs<ConversationRuntimeState.FinishResult.Observing>(
            state.finish(
                running = running,
                waitDirective = WAIT,
                nowEpochSecond = 100,
                allowPendingContinuation = true,
                onFinished = { released = true },
            )
        ).observation

        assertTrue(released)
        assertEquals(130L, observation.expiresAtEpochSecond)
        assertFalse(state.isExpectedUser(KEY, 999, nowEpochSecond = 101))
        assertTrue(state.isExpectedUser(KEY, TARGET_USER, nowEpochSecond = 101))

        val resumed = state.beginObserved(KEY, TARGET_USER, nowEpochSecond = 101)
        assertEquals(WAIT, resumed?.resumedWait)
        assertFalse(state.isExpectedUser(KEY, TARGET_USER, nowEpochSecond = 101))
        assertNull(state.beginObserved(KEY, TARGET_USER, nowEpochSecond = 101))
    }

    @Test
    fun observationExpiresSilentlyAndIsScopedToTheFullConversationKey() {
        val state = ConversationRuntimeState<String>()
        val running = assertIs<ConversationRuntimeState.BeginResult.Started<String>>(
            state.beginExplicit(KEY, "first")
        ).running
        state.finish(
            running = running,
            waitDirective = WAIT,
            nowEpochSecond = 100,
            allowPendingContinuation = true,
            onFinished = {},
        )

        val otherGroup = KEY.copy(subjectId = KEY.subjectId + 1)
        val otherBot = KEY.copy(botId = KEY.botId + 1)
        assertFalse(state.isExpectedUser(otherGroup, TARGET_USER, nowEpochSecond = 101))
        assertFalse(state.isExpectedUser(otherBot, TARGET_USER, nowEpochSecond = 101))
        assertFalse(state.isExpectedUser(KEY, TARGET_USER, nowEpochSecond = 130))
    }

    private companion object {
        const val TARGET_USER = 123L
        val KEY = ConversationKey(
            botId = 1,
            kind = MessageSourceKind.GROUP,
            subjectId = 2,
        )
        val WAIT = FollowUpWaitDirective(
            timeoutSeconds = 30,
            fromUserIds = setOf(TARGET_USER),
            condition = "等待对方补充版本信息",
        )
    }
}
