package top.jie65535.mirai.profile

import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileDailyMaintenanceTest {
    @Test
    fun parsesStrictDailyUpdateTime() {
        assertEquals(LocalTime.of(4, 30), parseProfileDailyUpdateTime("04:30"))
        assertEquals(LocalTime.of(23, 59), parseProfileDailyUpdateTime(" 23:59 "))
        assertNull(parseProfileDailyUpdateTime("4:30"))
        assertNull(parseProfileDailyUpdateTime("24:00"))
        assertNull(parseProfileDailyUpdateTime("04:60"))
    }

    @Test
    fun schedulesTodayOrTomorrowInServerTimeZone() {
        val zone = ZoneId.of("Asia/Shanghai")
        val scheduledTime = LocalTime.of(4, 30)

        assertEquals(
            ZonedDateTime.of(2026, 8, 5, 4, 30, 0, 0, zone),
            nextProfileDailyUpdateAt(
                ZonedDateTime.of(2026, 8, 5, 3, 0, 0, 0, zone),
                scheduledTime,
            ),
        )
        assertEquals(
            ZonedDateTime.of(2026, 8, 6, 4, 30, 0, 0, zone),
            nextProfileDailyUpdateAt(
                ZonedDateTime.of(2026, 8, 5, 4, 30, 0, 0, zone),
                scheduledTime,
            ),
        )
    }

    @Test
    fun calculatesOldestAllowedPendingMessageTime() {
        assertEquals(395_200, oldestAllowedProfilePendingTime(1_000_000, 7))
        assertEquals(0, oldestAllowedProfilePendingTime(100, 7))
    }
}
