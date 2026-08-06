package top.jie65535.mirai.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.jie65535.mirai.llm.normalizeMaxConcurrentRequests

/**
 * Run-scoped admission controller for the online daily group maintenance job.
 *
 * The shared ModelService semaphore remains the hard upper bound. This gate
 * only controls this one bulk run: it ramps healthy admission from one request,
 * pauses fresh work after a failure, and keeps recovery retries single-flight.
 */
internal class ProfileDailyRequestController(
    maxConcurrentRequests: Int,
    private val maxAttempts: Int,
    private val growthFactor: Int = 4,
    private val maxConsecutiveExhaustedTasks: Int = 3,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(growthFactor >= 2) { "growthFactor must be at least 2" }
        require(maxConsecutiveExhaustedTasks > 0) {
            "maxConsecutiveExhaustedTasks must be positive"
        }
    }

    private val maxConcurrency = normalizeMaxConcurrentRequests(maxConcurrentRequests)
    private val mutex = Mutex()
    private val waiters = ArrayDeque<Waiter>()
    private val active = LinkedHashMap<Long, Permit>()
    private val heldFailures = LinkedHashSet<ProfileDailyRequestKey>()
    private val exhaustedTasks = LinkedHashSet<ProfileDailyRequestKey>()

    private var nextPermitId = 1L
    private var admissionLimit = 1
    private var successesAtLimit = 0
    private var paused = false
    private var incidentOpen = false
    private var stopped = false
    private var consecutiveExhaustedTasks = 0
    private var freshProbeSuccesses = 0

    private var totalAttempts = 0
    private var countedFailures = 0
    private var probeAttempts = 0
    private var pauseIncidents = 0
    private var recoveredIncidents = 0
    private var peakActive = 0
    private var peakLimit = 1
    private var maxParallelProbes = 0

    /**
     * Admits and records one model attempt. A rejected/safety response can be
     * excluded from the global failure circuit without affecting the gate.
     */
    suspend fun <T> execute(
        key: ProfileDailyRequestKey,
        attempt: Int,
        countFailure: (Throwable) -> Boolean = { true },
        block: suspend () -> T,
    ): T {
        require(attempt in 1..maxAttempts) { "attempt must be within 1..$maxAttempts" }
        val permit = awaitPermit(key, attempt)
        return try {
            val result = block()
            onSuccess(permit)
            result
        } catch (cause: CancellationException) {
            onCancellation(permit)
            throw cause
        } catch (cause: Throwable) {
            if (countFailure(cause)) onFailure(permit, attempt)
            else release(permit)
            throw cause
        }
    }

    suspend fun snapshot(): ProfileDailyRequestControllerStats = mutex.withLock {
        ProfileDailyRequestControllerStats(
            totalAttempts = totalAttempts,
            countedFailures = countedFailures,
            probeAttempts = probeAttempts,
            pauseIncidents = pauseIncidents,
            recoveredIncidents = recoveredIncidents,
            exhaustedTasks = exhaustedTasks.size,
            stopped = stopped,
            peakActive = peakActive,
            peakLimit = peakLimit,
            finalLimit = admissionLimit,
            maxParallelProbes = maxParallelProbes,
        )
    }

    private suspend fun awaitPermit(
        key: ProfileDailyRequestKey,
        attempt: Int,
    ): Permit {
        val waiter = Waiter(key, attempt)
        mutex.withLock {
            if (stopped) {
                waiter.deferred.completeExceptionally(ProfileDailyRunStoppedException)
            } else {
                waiters.addLast(waiter)
                pumpLocked()
            }
        }
        return try {
            waiter.deferred.await()
        } catch (cause: CancellationException) {
            mutex.withLock {
                waiters.remove(waiter)
                waiter.permit?.let { permit -> active.remove(permit.id) }
                waiter.deferred.cancel(cause)
                pumpLocked()
            }
            throw cause
        }
    }

    private suspend fun onSuccess(permit: Permit) {
        mutex.withLock {
            active.remove(permit.id)
            if (permit.kind == PermitKind.RECOVERY) {
                heldFailures.remove(permit.key)
                if (paused) resumeAfterRecoverySuccessLocked()
            } else if (permit.kind == PermitKind.FRESH_PROBE) {
                freshProbeSuccesses++
            }
            consecutiveExhaustedTasks = 0
            successesAtLimit++
            if (admissionLimit < maxConcurrency && successesAtLimit >= admissionLimit) {
                admissionLimit = minOf(maxConcurrency, admissionLimit * growthFactor)
                successesAtLimit = 0
                peakLimit = maxOf(peakLimit, admissionLimit)
            }
            if (paused && heldFailures.isEmpty() && freshProbeSuccesses >= FRESH_PROBE_SUCCESS_TARGET) {
                resumeAfterFreshProbesLocked()
            }
            pumpLocked()
        }
    }

    private suspend fun onFailure(permit: Permit, attempt: Int) {
        mutex.withLock {
            active.remove(permit.id)
            countedFailures++
            if (stopped) return@withLock

            if (!incidentOpen) {
                incidentOpen = true
                paused = true
                pauseIncidents++
            }
            heldFailures.add(permit.key)
            if (attempt >= maxAttempts) {
                heldFailures.remove(permit.key)
                if (exhaustedTasks.add(permit.key)) consecutiveExhaustedTasks++
                if (consecutiveExhaustedTasks >= maxConsecutiveExhaustedTasks) {
                    stopped = true
                    failWaitingLocked()
                }
            }
            pumpLocked()
        }
    }

    private suspend fun onCancellation(permit: Permit) {
        mutex.withLock {
            active.remove(permit.id)
            pumpLocked()
        }
    }

    private suspend fun release(permit: Permit) {
        mutex.withLock {
            active.remove(permit.id)
            pumpLocked()
        }
    }

    private fun resumeAfterRecoverySuccessLocked() {
        paused = false
        if (incidentOpen) {
            recoveredIncidents++
            incidentOpen = false
        }
        freshProbeSuccesses = 0
    }

    private fun resumeAfterFreshProbesLocked() {
        paused = false
        if (incidentOpen) {
            recoveredIncidents++
            incidentOpen = false
        }
        freshProbeSuccesses = 0
    }

    private fun pumpLocked() {
        removeInactiveWaitersLocked()
        if (stopped) return

        while (active.size < admissionLimit) {
            val waiter = when {
                !paused -> findNormalOrRecoveryWaiterLocked()
                heldFailures.isNotEmpty() -> {
                    if (active.values.any { it.kind == PermitKind.RECOVERY }) null
                    else waiters.firstOrNull { it.attempt > 1 && heldFailures.contains(it.key) }
                }
                freshProbeSuccesses < FRESH_PROBE_SUCCESS_TARGET -> {
                    waiters.firstOrNull { it.attempt == 1 }
                }
                else -> null
            } ?: break

            waiters.remove(waiter)
            val kind = when {
                waiter.attempt > 1 && heldFailures.contains(waiter.key) -> PermitKind.RECOVERY
                paused -> PermitKind.FRESH_PROBE
                else -> PermitKind.NORMAL
            }
            val permit = Permit(nextPermitId++, waiter.key, kind)
            waiter.permit = permit
            active[permit.id] = permit
            totalAttempts++
            if (kind != PermitKind.NORMAL) probeAttempts++
            peakActive = maxOf(peakActive, active.size)
            maxParallelProbes = maxOf(
                maxParallelProbes,
                active.values.count { it.kind != PermitKind.NORMAL },
            )
            waiter.deferred.complete(permit)

            if (kind == PermitKind.RECOVERY) break
            if (paused) break
        }
    }

    private fun findNormalOrRecoveryWaiterLocked(): Waiter? {
        if (heldFailures.isNotEmpty() && active.values.none { it.kind == PermitKind.RECOVERY }) {
            return waiters.firstOrNull { it.attempt > 1 && heldFailures.contains(it.key) }
        }
        return waiters.firstOrNull { it.attempt == 1 }
    }

    private fun removeInactiveWaitersLocked() {
        waiters.removeAll { !it.deferred.isActive }
    }

    private fun failWaitingLocked() {
        waiters.forEach { it.deferred.completeExceptionally(ProfileDailyRunStoppedException) }
        waiters.clear()
    }

    private data class Waiter(
        val key: ProfileDailyRequestKey,
        val attempt: Int,
        val deferred: CompletableDeferred<Permit> = CompletableDeferred(),
        var permit: Permit? = null,
    )

    private data class Permit(
        val id: Long,
        val key: ProfileDailyRequestKey,
        val kind: PermitKind,
    )

    private enum class PermitKind {
        NORMAL,
        RECOVERY,
        FRESH_PROBE,
    }

    companion object {
        private const val FRESH_PROBE_SUCCESS_TARGET = 2
    }
}

internal data class ProfileDailyRequestKey(
    val groupId: Long,
    val startTime: Int,
    val endTime: Int,
)

internal data class ProfileDailyRequestControllerStats(
    val totalAttempts: Int,
    val countedFailures: Int,
    val probeAttempts: Int,
    val pauseIncidents: Int,
    val recoveredIncidents: Int,
    val exhaustedTasks: Int,
    val stopped: Boolean,
    val peakActive: Int,
    val peakLimit: Int,
    val finalLimit: Int,
    val maxParallelProbes: Int,
)

internal object ProfileDailyRunStoppedException : RuntimeException(
    "每日群画像推进已因连续模型请求失败而停止",
)
