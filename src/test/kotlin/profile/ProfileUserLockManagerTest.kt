package top.jie65535.mirai.profile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileUserLockManagerTest {
    @Test
    fun cancellationWhileWaitingReleasesAlreadyAcquiredUserLocks() = runBlocking {
        val locks = ProfileUserLockManager()
        val secondUserLocked = CompletableDeferred<Unit>()
        val releaseSecondUser = CompletableDeferred<Unit>()

        coroutineScope {
            val holder = async {
                locks.withUserLocks(listOf(2)) {
                    secondUserLocked.complete(Unit)
                    releaseSecondUser.await()
                }
            }
            secondUserLocked.await()
            val waiter = async {
                locks.withUserLocks(listOf(1, 2)) { error("已取消任务不应进入临界区") }
            }
            delay(50)
            waiter.cancelAndJoin()

            withTimeout(1_000) {
                locks.withUserLocks(listOf(1)) { }
            }
            releaseSecondUser.complete(Unit)
            holder.await()
        }

        assertEquals(0, locks.activeLockCount)
    }
}
