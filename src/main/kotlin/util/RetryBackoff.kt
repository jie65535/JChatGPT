package top.jie65535.mirai.util

import top.jie65535.mirai.config.PluginConfig
import kotlin.random.Random

internal class RetryBackoff(
    initialDelayMillis: Long,
    maxDelayMillis: Long,
    private val randomFraction: () -> Double = { Random.nextDouble() },
) {
    private val initialDelayMillis = initialDelayMillis.coerceIn(0L, MAX_DELAY_MILLIS)
    private val maxDelayMillis = maxDelayMillis.coerceIn(0L, MAX_DELAY_MILLIS)

    /**
     * Returns the delay before the given retry. Retry numbers start at 1.
     * A 20% downward jitter prevents concurrent failures from retrying in lockstep.
     */
    fun delayMillis(retryNumber: Int): Long {
        if (retryNumber <= 0 || initialDelayMillis == 0L || maxDelayMillis == 0L) return 0L

        var nominalDelay = minOf(initialDelayMillis, maxDelayMillis)
        repeat((retryNumber - 1).coerceAtMost(MAX_EXPONENT)) {
            nominalDelay = when {
                nominalDelay >= maxDelayMillis -> maxDelayMillis
                nominalDelay > maxDelayMillis / 2 -> maxDelayMillis
                else -> nominalDelay * 2
            }
        }

        val jitterWindow = nominalDelay / JITTER_DIVISOR
        if (jitterWindow == 0L) return nominalDelay
        val fraction = randomFraction().coerceIn(0.0, 1.0)
        return nominalDelay - jitterWindow + (jitterWindow * fraction).toLong()
    }

    companion object {
        private const val JITTER_DIVISOR = 5L
        private const val MAX_EXPONENT = 62
        const val MAX_DELAY_MILLIS = 60_000L

        fun fromConfig(): RetryBackoff = RetryBackoff(
            initialDelayMillis = PluginConfig.retryBackoffBaseMillis,
            maxDelayMillis = PluginConfig.retryBackoffMaxMillis,
        )
    }
}
