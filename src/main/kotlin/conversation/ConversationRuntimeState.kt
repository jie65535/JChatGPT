package top.jie65535.mirai.conversation

import kotlinx.coroutines.Job

internal class ConversationRuntimeState<E> {
    internal class Running<E> internal constructor(
        val key: ConversationKey,
    ) {
        internal var pendingEvent: E? = null
    }

    internal class Observation internal constructor(
        val key: ConversationKey,
        val directive: FollowUpWaitDirective,
        val expiresAtEpochSecond: Long,
    ) {
        internal var timeoutJob: Job? = null
    }

    internal sealed interface BeginResult<out E> {
        data class Started<E>(
            val running: Running<E>,
            val resumedWait: FollowUpWaitDirective? = null,
        ) : BeginResult<E>

        data class Queued(val newlyQueued: Boolean) : BeginResult<Nothing>
    }

    internal sealed interface FinishResult<out E> {
        data class Continue<E>(val event: E) : FinishResult<E>
        data class Observing(val observation: Observation) : FinishResult<Nothing>
        data object Ended : FinishResult<Nothing>
    }

    private sealed interface Slot<E>
    private data class RunningSlot<E>(val running: Running<E>) : Slot<E>
    private data class ObservationSlot<E>(val observation: Observation) : Slot<E>

    private val lock = Any()
    private val slots = mutableMapOf<ConversationKey, Slot<E>>()

    fun beginExplicit(key: ConversationKey, event: E): BeginResult<E> = synchronized(lock) {
        when (val slot = slots[key]) {
            is RunningSlot -> {
                val newlyQueued = slot.running.pendingEvent == null
                if (newlyQueued) slot.running.pendingEvent = event
                BeginResult.Queued(newlyQueued)
            }

            is ObservationSlot -> {
                slot.observation.timeoutJob?.cancel()
                startRunning(key)
            }

            null -> startRunning(key)
        }
    }

    fun isExpectedUser(key: ConversationKey, userId: Long, nowEpochSecond: Long): Boolean = synchronized(lock) {
        val observation = (slots[key] as? ObservationSlot)?.observation ?: return@synchronized false
        if (nowEpochSecond >= observation.expiresAtEpochSecond) {
            slots.remove(key)
            observation.timeoutJob?.cancel()
            return@synchronized false
        }
        userId in observation.directive.fromUserIds
    }

    fun beginObserved(
        key: ConversationKey,
        userId: Long,
        nowEpochSecond: Long,
    ): BeginResult.Started<E>? = synchronized(lock) {
        val observation = (slots[key] as? ObservationSlot)?.observation ?: return@synchronized null
        if (nowEpochSecond >= observation.expiresAtEpochSecond) {
            slots.remove(key)
            observation.timeoutJob?.cancel()
            return@synchronized null
        }
        if (userId !in observation.directive.fromUserIds) return@synchronized null

        observation.timeoutJob?.cancel()
        val running = Running<E>(key)
        slots[key] = RunningSlot(running)
        BeginResult.Started(running, observation.directive)
    }

    fun takePending(running: Running<E>): E? = synchronized(lock) {
        val active = (slots[running.key] as? RunningSlot)?.running
        if (active !== running) return@synchronized null
        running.pendingEvent.also { running.pendingEvent = null }
    }

    fun finish(
        running: Running<E>,
        waitDirective: FollowUpWaitDirective?,
        nowEpochSecond: Long,
        allowPendingContinuation: Boolean,
        onFinished: () -> Unit,
    ): FinishResult<E> = synchronized(lock) {
        val active = (slots[running.key] as? RunningSlot)?.running
        if (active !== running) {
            onFinished()
            return@synchronized FinishResult.Ended
        }

        val pending = running.pendingEvent
        running.pendingEvent = null
        if (pending != null && allowPendingContinuation) {
            return@synchronized FinishResult.Continue(pending)
        }

        onFinished()
        if (pending == null && waitDirective != null) {
            val observation = Observation(
                key = running.key,
                directive = waitDirective,
                expiresAtEpochSecond = nowEpochSecond + waitDirective.timeoutSeconds,
            )
            slots[running.key] = ObservationSlot(observation)
            FinishResult.Observing(observation)
        } else {
            slots.remove(running.key)
            FinishResult.Ended
        }
    }

    fun abort(running: Running<E>, onFinished: () -> Unit) = synchronized(lock) {
        val active = (slots[running.key] as? RunningSlot)?.running
        if (active === running) slots.remove(running.key)
        onFinished()
    }

    fun attachTimeoutJob(observation: Observation, job: Job) {
        val attached = synchronized(lock) {
            val active = (slots[observation.key] as? ObservationSlot)?.observation
            if (active === observation) {
                observation.timeoutJob = job
                true
            } else {
                false
            }
        }
        if (!attached) job.cancel()
    }

    fun expire(observation: Observation): Boolean = synchronized(lock) {
        val active = (slots[observation.key] as? ObservationSlot)?.observation
        if (active !== observation) return@synchronized false
        slots.remove(observation.key)
        true
    }

    fun clear() {
        val jobs = synchronized(lock) {
            slots.values.mapNotNull { (it as? ObservationSlot)?.observation?.timeoutJob }
                .also { slots.clear() }
        }
        jobs.forEach { it.cancel() }
    }

    private fun startRunning(key: ConversationKey): BeginResult.Started<E> {
        val running = Running<E>(key)
        slots[key] = RunningSlot(running)
        return BeginResult.Started(running)
    }
}
