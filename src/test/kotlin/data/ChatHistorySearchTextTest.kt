package top.jie65535.mirai.data

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ChatHistorySearchTextTest {
    @Test
    fun rendersAtTargetNamesAndBuildsBigrams() {
        val code = """
            [{"type":"At","target":2180487691}, {"type":"PlainText","content":" 筱玥"}]
        """.trimIndent()

        assertEquals(setOf(2180487691L), ChatHistorySearchText.extractAtTargets(code))
        assertEquals("@筱玥 筱玥", ChatHistorySearchText.extract(code, mapOf(2180487691L to "筱玥")))
        assertEquals("筱玥 筱玥", ChatHistorySearchText.bigrams("筱玥 筱玥"))
    }
}
