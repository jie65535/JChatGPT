package top.jie65535.mirai.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConversationRetryPolicyTest {
    @Test
    fun singleEndpointIsRetriedOnce() {
        assertEquals(0, nextChatEndpointIndex(endpointCount = 1, currentIndex = 0, failureCount = 1))
        assertEquals(null, nextChatEndpointIndex(endpointCount = 1, currentIndex = 0, failureCount = 2))
    }

    @Test
    fun multipleEndpointsAreEachAttemptedOnce() {
        assertEquals(1, nextChatEndpointIndex(endpointCount = 3, currentIndex = 0, failureCount = 1))
        assertEquals(2, nextChatEndpointIndex(endpointCount = 3, currentIndex = 1, failureCount = 2))
        assertEquals(null, nextChatEndpointIndex(endpointCount = 3, currentIndex = 2, failureCount = 3))
    }

    @Test
    fun successfulFallbackRemainsTheStartingEndpointForLaterRounds() {
        assertEquals(2, nextChatEndpointIndex(endpointCount = 3, currentIndex = 1, failureCount = 1))
    }

    @Test
    fun rejectsInvalidEndpointState() {
        assertFailsWith<IllegalArgumentException> {
            nextChatEndpointIndex(endpointCount = 0, currentIndex = 0, failureCount = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            nextChatEndpointIndex(endpointCount = 2, currentIndex = 2, failureCount = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            nextChatEndpointIndex(endpointCount = 2, currentIndex = 0, failureCount = 0)
        }
    }
}
