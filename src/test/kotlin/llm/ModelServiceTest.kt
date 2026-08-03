package top.jie65535.mirai.llm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class ModelServiceTest {
    private val service = ModelService(
        baseUrl = "http://localhost/",
        token = "test",
        timeout = 1.seconds,
        firstChunkTimeout = 1.seconds,
    )

    @Test
    fun readsStandardCachedPromptTokens() {
        val usage = service.extractCacheUsage(
            """{"usage":{"prompt_tokens":100,"prompt_tokens_details":{"cached_tokens":75}}}"""
        )

        assertEquals(ModelService.CacheUsage(hitTokens = 75, missTokens = 25), usage)
    }

    @Test
    fun readsDeepSeekCacheTokens() {
        val usage = service.extractCacheUsage(
            """{"usage":{"prompt_cache_hit_tokens":80,"prompt_cache_miss_tokens":20}}"""
        )

        assertEquals(ModelService.CacheUsage(hitTokens = 80, missTokens = 20), usage)
    }

    @Test
    fun ignoresResponsesWithoutCacheDetails() {
        assertNull(service.extractCacheUsage("""{"usage":{"prompt_tokens":100}}"""))
    }

    @Test
    fun configuresGlobalAndPerHostConcurrencyTogether() {
        val dispatcher = createRequestDispatcher(128)

        assertEquals(128, dispatcher.maxRequests)
        assertEquals(128, dispatcher.maxRequestsPerHost)
    }

    @Test
    fun clampsConcurrencyToSafetyRange() {
        assertEquals(1, normalizeMaxConcurrentRequests(0))
        assertEquals(MAX_MODEL_CONCURRENT_REQUESTS, normalizeMaxConcurrentRequests(Int.MAX_VALUE))
    }

    @Test
    fun queuesFlowsBeforeStartingWork() = runBlocking {
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val started = AtomicInteger()
        val firstWaveStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = flow {
            val currentActive = active.incrementAndGet()
            maximumActive.accumulateAndGet(currentActive, ::maxOf)
            if (started.incrementAndGet() == 2) firstWaveStarted.complete(Unit)
            try {
                release.await()
                emit(Unit)
            } finally {
                active.decrementAndGet()
            }
        }
        val semaphore = Semaphore(2)

        coroutineScope {
            val collectors = (1..6).map {
                async { source.withConcurrencyLimit(semaphore).collect() }
            }
            withTimeout(1_000) { firstWaveStarted.await() }
            assertEquals(2, active.get())
            release.complete(Unit)
            collectors.awaitAll()
        }

        assertEquals(2, maximumActive.get())
        assertEquals(6, started.get())
    }
}
