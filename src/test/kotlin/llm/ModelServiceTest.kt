package top.jie65535.mirai.llm

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
}
