package top.jie65535.mirai.conversation

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GroupChatTriggerPolicyTest {
    @Test
    fun disabledRestrictionDoesNotLookUpOwner() {
        assertTrue(allowsGroupChatTrigger(false, 0, { error("Unexpected lookup") }, { throw it }))
    }

    @Test
    fun missingOrInvalidOwnerBlocksWithoutLookup() {
        for (ownerId in listOf(0L, -1L)) {
            assertFalse(allowsGroupChatTrigger(true, ownerId, { error("Unexpected lookup") }, { throw it }))
        }
    }

    @Test
    fun configuredOwnerMustBePresentAndMembershipIsCheckedAgainOnEachTrigger() {
        val members = mutableSetOf(123L, 456L)
        val isMember: (Long) -> Boolean = { ownerId ->
            assertEquals(123L, ownerId)
            ownerId in members
        }
        assertTrue(allowsGroupChatTrigger(true, 123L, isMember, { throw it }))
        members.remove(123L)
        assertFalse(allowsGroupChatTrigger(true, 123L, isMember, { throw it }))
        members.add(123L)
        assertTrue(allowsGroupChatTrigger(true, 123L, isMember, { throw it }))
    }

    @Test
    fun unavailableMemberLookupBlocksAndReportsFailure() {
        val failure = UnsupportedOperationException("Member lookup unavailable")
        var reported: Exception? = null
        assertFalse(allowsGroupChatTrigger(true, 123L, { throw failure }, { reported = it }))
        assertSame(failure, reported)
    }

    @Test
    fun cancellationPropagatesWithoutReportingLookupFailure() {
        val cancellation = CancellationException("Cancelled")
        assertSame(cancellation, assertFailsWith<CancellationException> {
            allowsGroupChatTrigger(true, 123L, { throw cancellation }, { error("Unexpected failure report") })
        })
    }
}
