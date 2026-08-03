package top.jie65535.mirai.util

import kotlin.test.Test
import kotlin.test.assertEquals

class RetryBackoffTest {
    @Test
    fun growsExponentiallyAndStopsAtMaximum() {
        val backoff = RetryBackoff(
            initialDelayMillis = 1_000,
            maxDelayMillis = 10_000,
            randomFraction = { 1.0 },
        )

        assertEquals(1_000, backoff.delayMillis(1))
        assertEquals(2_000, backoff.delayMillis(2))
        assertEquals(4_000, backoff.delayMillis(3))
        assertEquals(8_000, backoff.delayMillis(4))
        assertEquals(10_000, backoff.delayMillis(5))
        assertEquals(10_000, backoff.delayMillis(Int.MAX_VALUE))
    }

    @Test
    fun appliesBoundedDownwardJitter() {
        val minimum = RetryBackoff(1_000, 10_000) { 0.0 }
        val maximum = RetryBackoff(1_000, 10_000) { 1.0 }

        assertEquals(800, minimum.delayMillis(1))
        assertEquals(1_000, maximum.delayMillis(1))
        assertEquals(3_200, minimum.delayMillis(3))
        assertEquals(4_000, maximum.delayMillis(3))
    }

    @Test
    fun zeroOrInvalidRetryDisablesDelay() {
        assertEquals(0, RetryBackoff(1_000, 10_000).delayMillis(0))
        assertEquals(0, RetryBackoff(0, 10_000).delayMillis(1))
        assertEquals(0, RetryBackoff(1_000, 0).delayMillis(1))
    }

    @Test
    fun clampsMisconfiguredValuesToSafetyLimit() {
        val backoff = RetryBackoff(Long.MAX_VALUE, Long.MAX_VALUE) { 1.0 }

        assertEquals(RetryBackoff.MAX_DELAY_MILLIS, backoff.delayMillis(1))
        assertEquals(RetryBackoff.MAX_DELAY_MILLIS, backoff.delayMillis(Int.MAX_VALUE))
    }
}
