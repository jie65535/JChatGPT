package top.jie65535.mirai.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProfileCommandArgumentsTest {
    @Test
    fun parsesAndDeduplicatesMultipleGroupIds() {
        assertEquals(
            listOf(111L, 222L, 333L),
            parseProfileGroupIds("111, 222，333;111"),
        )
    }

    @Test
    fun keepsSingleGroupCommandCompatible() {
        assertEquals(listOf(818800431L), parseProfileGroupIds("818800431"))
    }

    @Test
    fun parsesMultipleUserIds() {
        assertEquals(listOf(100L, 200L), parseProfileUserIds("100,200,100"))
    }

    @Test
    fun rejectsInvalidGroupId() {
        assertFailsWith<IllegalStateException> { parseProfileGroupIds("111,abc") }
    }
}
