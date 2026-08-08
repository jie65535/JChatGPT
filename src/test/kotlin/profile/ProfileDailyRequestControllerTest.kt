package top.jie65535.mirai.profile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileDailyRequestControllerTest {
    @Test
    fun growsHealthyAdmissionByFourWithoutExceedingTheConfiguredCap() = runBlocking {
        val controller = controller(maxConcurrency = 128)

        succeed(controller, key(1))
        assertEquals(4, controller.snapshot().finalLimit)

        repeat(4) { index -> succeed(controller, key(index + 2)) }
        assertEquals(16, controller.snapshot().finalLimit)

        repeat(16) { index -> succeed(controller, key(index + 6)) }
        assertEquals(64, controller.snapshot().finalLimit)

        repeat(64) { index -> succeed(controller, key(index + 22)) }
        val stats = controller.snapshot()
        assertEquals(128, stats.finalLimit)
        assertEquals(128, stats.peakLimit)
        assertFalse(stats.stopped)
    }

    @Test
    fun pausesFreshAdmissionUntilTheFailedTaskRecovers() = runBlocking {
        val controller = controller(maxConcurrency = 4)
        succeed(controller, key(1))
        val failedKey = key(2)
        fail(controller, failedKey, attempt = 1)
        val freshStarted = CompletableDeferred<Unit>()

        coroutineScope {
            val fresh = async {
                controller.execute(key(3), attempt = 1) {
                    freshStarted.complete(Unit)
                }
            }
            assertNull(withTimeoutOrNull(50) { freshStarted.await() })

            succeed(controller, failedKey, attempt = 2)
            withTimeout(1_000) { freshStarted.await() }
            fresh.await()
        }

        val stats = controller.snapshot()
        assertEquals(1, stats.countedFailures)
        assertEquals(1, stats.recoveredIncidents)
        assertEquals(1, stats.maxParallelProbes)
        assertFalse(stats.stopped)
    }

    @Test
    fun admitsNewWorkWhenOneFastRequestFinishesBeforeSlowRequests() = runBlocking {
        val controller = controller(maxConcurrency = 4)
        succeed(controller, key(1))
        val allEntered = CompletableDeferred<Unit>()
        val releaseFast = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        val entered = AtomicInteger()

        coroutineScope {
            val activeRequests = (2..5).map { index ->
                async {
                    controller.execute(key(index), attempt = 1) {
                        if (entered.incrementAndGet() == 4) allEntered.complete(Unit)
                        if (index == 2) releaseFast.await() else releaseSlow.await()
                    }
                }
            }
            withTimeout(1_000) { allEntered.await() }

            val newWorkStarted = CompletableDeferred<Unit>()
            val queued = async {
                controller.execute(key(6), attempt = 1) {
                    newWorkStarted.complete(Unit)
                }
            }
            assertNull(withTimeoutOrNull(50) { newWorkStarted.await() })
            releaseFast.complete(Unit)
            withTimeout(1_000) { newWorkStarted.await() }
            releaseSlow.complete(Unit)
            activeRequests.forEach { it.await() }
            queued.await()
        }

        assertFalse(controller.snapshot().stopped)
    }

    @Test
    fun keepsMultipleFailedTaskRetriesSingleFlight() = runBlocking {
        val controller = controller(maxConcurrency = 4)
        succeed(controller, key(1))
        val failedKeys = listOf(key(2), key(3))
        val allInitialAttemptsEntered = CompletableDeferred<Unit>()
        val releaseInitialAttempts = CompletableDeferred<Unit>()
        val initialEntered = AtomicInteger()

        coroutineScope {
            val initialAttempts = (failedKeys + key(4) + key(5)).mapIndexed { index, requestKey ->
                async {
                    runCatching {
                        controller.execute(requestKey, attempt = 1) {
                            if (initialEntered.incrementAndGet() == 4) {
                                allInitialAttemptsEntered.complete(Unit)
                            }
                            releaseInitialAttempts.await()
                            if (index < 2) throw IOException("transient")
                        }
                    }
                }
            }
            withTimeout(1_000) { allInitialAttemptsEntered.await() }
            releaseInitialAttempts.complete(Unit)
            initialAttempts.forEach { it.await() }

            val activeRetries = AtomicInteger()
            val peakRetries = AtomicInteger()
            val firstRetryEntered = CompletableDeferred<Unit>()
            val releaseRetries = CompletableDeferred<Unit>()
            val retries = failedKeys.map { requestKey ->
                async {
                    controller.execute(requestKey, attempt = 2) {
                        val current = activeRetries.incrementAndGet()
                        peakRetries.updateAndGet { previous -> maxOf(previous, current) }
                        firstRetryEntered.complete(Unit)
                        releaseRetries.await()
                        activeRetries.decrementAndGet()
                    }
                }
            }
            withTimeout(1_000) { firstRetryEntered.await() }
            delay(50)
            assertEquals(1, peakRetries.get())
            releaseRetries.complete(Unit)
            retries.forEach { it.await() }
        }

        val stats = controller.snapshot()
        assertEquals(1, stats.maxParallelProbes)
        assertEquals(0, stats.exhaustedTasks)
        assertFalse(stats.stopped)
    }

    @Test
    fun stopsAfterThreeDifferentTasksExhaustTheirAttempts() = runBlocking {
        val controller = controller(maxConcurrency = 128)

        repeat(3) { taskIndex ->
            repeat(3) { attemptIndex ->
                fail(controller, key(taskIndex + 1), attemptIndex + 1)
            }
        }

        val stats = controller.snapshot()
        assertTrue(stats.stopped)
        assertEquals(9, stats.totalAttempts)
        assertEquals(9, stats.countedFailures)
        assertEquals(8, stats.probeAttempts)
        assertEquals(3, stats.exhaustedTasks)
        assertFailsWith<ProfileDailyRunStoppedException> {
            controller.execute(key(4), attempt = 1) { }
        }
        Unit
    }

    @Test
    fun onePersistentBadTaskDoesNotStopHealthyWork() = runBlocking {
        val controller = controller(maxConcurrency = 128)
        repeat(3) { attemptIndex -> fail(controller, key(1), attemptIndex + 1) }

        assertFalse(controller.snapshot().stopped)
        succeed(controller, key(2))
        succeed(controller, key(3))
        succeed(controller, key(4))

        val stats = controller.snapshot()
        assertFalse(stats.stopped)
        assertEquals(1, stats.exhaustedTasks)
        assertEquals(1, stats.recoveredIncidents)
    }

    @Test
    fun canStopTheRunAfterTheFirstTaskExhaustsItsAttempts() = runBlocking {
        val controller = ProfileDailyRequestController(
            maxConcurrentRequests = 128,
            maxAttempts = 3,
            maxConsecutiveExhaustedTasks = 1,
        )

        repeat(3) { attemptIndex -> fail(controller, key(1), attemptIndex + 1) }

        val stats = controller.snapshot()
        assertTrue(stats.stopped)
        assertEquals(1, stats.exhaustedTasks)
        assertFailsWith<ProfileDailyRunStoppedException> {
            controller.execute(key(2), attempt = 1) { }
        }
        Unit
    }

    @Test
    fun cancellationWhileWaitingDoesNotLeaveAQueuedRequest() = runBlocking {
        val controller = controller(maxConcurrency = 1)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        coroutineScope {
            val first = async {
                controller.execute(key(1), attempt = 1) {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                }
            }
            firstEntered.await()
            val cancelled = async {
                controller.execute(key(2), attempt = 1) {
                    error("cancelled waiter must not start")
                }
            }
            delay(50)
            cancelled.cancelAndJoin()
            releaseFirst.complete(Unit)
            first.await()
        }

        succeed(controller, key(3))
        assertEquals(2, controller.snapshot().totalAttempts)
    }

    private fun controller(maxConcurrency: Int) = ProfileDailyRequestController(
        maxConcurrentRequests = maxConcurrency,
        maxAttempts = 3,
        growthFactor = 4,
        maxConsecutiveExhaustedTasks = 3,
    )

    private suspend fun succeed(
        controller: ProfileDailyRequestController,
        key: ProfileDailyRequestKey,
        attempt: Int = 1,
    ) {
        controller.execute(key, attempt) { }
    }

    private suspend fun fail(
        controller: ProfileDailyRequestController,
        key: ProfileDailyRequestKey,
        attempt: Int,
    ) {
        assertFailsWith<IOException> {
            controller.execute(key, attempt) { throw IOException("unavailable") }
        }
    }

    private fun key(index: Int) = ProfileDailyRequestKey(
        groupId = index.toLong(),
        startTime = index * 100,
        endTime = index * 100 + 50,
    )
}
