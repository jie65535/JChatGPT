package top.jie65535.mirai.tools

import kotlinx.serialization.json.*
import top.jie65535.mirai.config.ModelProviderDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeepSeekToolsTest {
    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `search preserves official optional fields and leaves absent defaults to server`() {
        val search = DeepSeekSearch()
        val full = args("""{"query":"Kotlin site:kotlinlang.org after:2026","count":50,"ori_question":"如何迁移 Kotlin？","localization":"203.0.113.1","need_content":true,"future_option":"value"}""")

        assertEquals(full, search.validateArguments(full))
        assertEquals(args("""{"query":"Kotlin"}"""), search.validateArguments(args("""{"query":"Kotlin"}""")))
        assertEquals(
            args("""{"query":"Kotlin","ori_question":null,"localization":""}"""),
            search.validateArguments(args("""{"query":"Kotlin","ori_question":null,"localization":""}""")),
        )
        assertEquals(
            setOf("query", "count", "ori_question", "localization", "need_content"),
            assertNotNull(search.tool.function.parameters).schema.jsonObject
                .getValue("properties").jsonObject.keys,
        )
    }

    @Test
    fun `search counts Unicode code points and rejects invalid values without coercion`() {
        val search = DeepSeekSearch()
        val emoji = "\uD83D\uDE00"
        search.validateArguments(buildJsonObject { put("query", emoji.repeat(128)) })
        for (query in listOf("", " \n\t", emoji.repeat(129))) {
            assertFailsWith<IllegalArgumentException> {
                search.validateArguments(buildJsonObject { put("query", query) })
            }
        }
        for (invalid in listOf(
            "{}", """{"query":12}""", """{"query":["a","b"]}""",
            """{"query":"a","count":0}""", """{"query":"a","count":51}""",
            """{"query":"a","count":"5"}""", """{"query":"a","count":1.5}""",
            """{"query":"a","count":null}""", """{"query":"a","need_content":"true"}""",
            """{"query":"a","need_content":null}""", """{"query":"a","ori_question":""}""",
            """{"query":"a","localization":123}""",
        )) {
            assertFailsWith<IllegalArgumentException>(invalid) { search.validateArguments(args(invalid)) }
        }
    }

    @Test
    fun `fetch preserves URL and enforces official URL constraints`() {
        val fetch = DeepSeekFetch()
        val url = "https://example.com/article?q=Kotlin%20API#section"
        val request = buildJsonObject { put("url", url) }
        assertEquals(request, fetch.validateArguments(request))
        fetch.validateArguments(buildJsonObject { put("url", "https://[2001:db8::1]/article") })
        val prefix = "https://example.com/"
        fetch.validateArguments(buildJsonObject { put("url", prefix + "a".repeat(8192 - prefix.length)) })
        for (invalid in listOf(
            "", "not a URL", "ftp://example.com/file", "https:///article", "https://user:password@example.com/",
            prefix + "a".repeat(8193 - prefix.length),
        )) {
            assertFailsWith<IllegalArgumentException> {
                fetch.validateArguments(buildJsonObject { put("url", invalid) })
            }
        }
        assertFailsWith<IllegalArgumentException> { fetch.validateArguments(args("""{"url":["https://example.com/"]}""")) }
    }

    @Test
    fun `provider credentials are independent of model bindings and normalize v1 only once`() {
        for (api in listOf("https://api.deepseek.com", "https://api.deepseek.com/", "https://api.deepseek.com/v1/")) {
            val provider = ModelProviderDefinition("tools-only", "openai", api, "test-key")
            val endpoint = assertNotNull(DeepSeekToolsClient.resolveEndpoint("tools-only", listOf(provider)))
            assertEquals("https://api.deepseek.com/v1", endpoint.api)
            assertEquals("tools-only", endpoint.provider)
            assertEquals("test-key", endpoint.token)
        }
        val provider = ModelProviderDefinition("tools", "openai", "https://api.deepseek.com/v1/", "test")
        assertNull(DeepSeekToolsClient.resolveEndpoint("", listOf(provider)))
        assertNull(DeepSeekToolsClient.resolveEndpoint("missing", listOf(provider)))
        assertNull(DeepSeekToolsClient.resolveEndpoint("tools", listOf(provider, provider)))
        for (invalid in listOf(
            provider.copy(token = " "), provider.copy(type = "dashscope"),
            provider.copy(api = "not a URL"), provider.copy(api = "file:///tmp/api"),
            provider.copy(api = "https://user:password@example.com/v1/"),
            provider.copy(api = "https://example.com/v1/?key=secret"),
            provider.copy(api = "https://example.com/v1/#fragment"),
        )) {
            assertNull(DeepSeekToolsClient.resolveEndpoint("tools", listOf(invalid)))
        }
    }

    @Test
    fun `normal output is byte for byte unchanged`() {
        val response = """{ "results": [{"content":"","summary":"官方摘要","site_name":null,"future_field":true}] }"""
        assertEquals(response, DeepSeekSearch().limitOutput(response, 1000))
    }

    @Test
    fun `oversized search output remains JSON and retains all result metadata`() {
        val response = buildJsonObject {
            putJsonArray("results") {
                repeat(3) { index ->
                    addJsonObject {
                        put("title", "Result $index")
                        put("url", "https://example.com/$index")
                        put("rerank_score", 2.4)
                        put("authority", 3)
                        put("date", JsonNull)
                        put("snippet", "short")
                        put("summary", "summary".repeat(1000))
                        put("content", "quote\"\\\n\uD83D\uDE00".repeat(1000))
                        put("future_field", true)
                    }
                }
            }
        }
        val output = DeepSeekSearch().limitOutput(response.toString(), 2000)
        assertTrue(output.length <= 2000)
        val results = Json.parseToJsonElement(output).jsonObject.getValue("results").jsonArray
        assertEquals(3, results.size)
        for ((index, result) in results.withIndex()) {
            val item = result.jsonObject
            assertEquals(response.getValue("results").jsonArray[index].jsonObject.keys, item.keys)
            assertEquals("https://example.com/$index", item.getValue("url").jsonPrimitive.content)
            assertEquals(2.4, item.getValue("rerank_score").jsonPrimitive.double)
            assertEquals(JsonNull, item["date"])
            assertEquals("short", item.getValue("snippet").jsonPrimitive.content)
            assertTrue(item.getValue("summary").jsonPrimitive.content.contains("已截断"))
            assertTrue(item.getValue("content").jsonPrimitive.content.contains("已截断"))
            assertFalse(item.getValue("content").jsonPrimitive.content.contains("\uFFFD"))
        }
    }

    @Test
    fun `fetch truncates escaped Markdown within JSON budget and reports oversized metadata`() {
        val response = buildJsonObject {
            put("title", "Title")
            put("content", "\"\\\n\uD83D\uDE00".repeat(1000))
        }
        val output = DeepSeekFetch().limitOutput(response.toString(), 300)
        assertTrue(output.length <= 300)
        val page = Json.parseToJsonElement(output).jsonObject
        assertEquals("Title", page.getValue("title").jsonPrimitive.content)
        assertTrue(page.getValue("content").jsonPrimitive.content.endsWith("已截断]"))

        val largeMetadata = buildJsonObject { put("title", "t".repeat(2000)); put("content", "") }
        val error = Json.parseToJsonElement(DeepSeekFetch().limitOutput(largeMetadata.toString(), 500)).jsonObject
        assertEquals("tool_output_limit", error.getValue("error").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("{}", DeepSeekFetch().limitOutput(response.toString(), 2))
    }
}
