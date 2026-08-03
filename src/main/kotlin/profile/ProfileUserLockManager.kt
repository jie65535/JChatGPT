package top.jie65535.mirai.profile

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

internal class ProfileUserLockManager {
    private val entries = ConcurrentHashMap<Long, LockEntry>()

    suspend fun <T> withUserLocks(userIds: Collection<Long>, block: suspend () -> T): T {
        val reserved = userIds.asSequence()
            .distinct()
            .sorted()
            .map { userId -> userId to reserve(userId) }
            .toList()
        val acquired = mutableListOf<LockEntry>()
        return try {
            reserved.forEach { (_, entry) ->
                entry.mutex.lock()
                acquired += entry
            }
            block()
        } finally {
            acquired.asReversed().forEach { entry -> entry.mutex.unlock() }
            reserved.forEach { (userId, entry) -> release(userId, entry) }
        }
    }

    internal val activeLockCount: Int
        get() = entries.size

    private fun reserve(userId: Long): LockEntry = entries.compute(userId) { _, current ->
        (current ?: LockEntry()).also { it.references++ }
    } ?: error("无法创建用户画像锁: $userId")

    private fun release(userId: Long, expected: LockEntry) {
        entries.computeIfPresent(userId) { _, current ->
            check(current === expected) { "用户画像锁状态不一致: $userId" }
            current.references--
            current.takeIf { it.references > 0 }
        }
    }

    private class LockEntry(
        val mutex: Mutex = Mutex(),
        var references: Int = 0,
    )
}
