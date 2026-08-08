package top.jie65535.mirai.conversation

import java.nio.file.Files
import net.mamoe.mirai.message.data.MessageSourceKind
import top.jie65535.mirai.data.ChatMessageRecord
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ConversationContextTest {
    @Test
    fun replyIndexKeepsStableNumbersForStoredMessageIds() {
        val index = ReplyIndex()
        val first = record(ids = "10,20", time = 100)
        val duplicate = record(ids = "10,20", time = 101)
        val withoutIds = record(ids = null, time = 102)

        assertEquals(1, index.add(first))
        assertEquals(1, index.add(duplicate))
        assertEquals(2, index.add(withoutIds))
        assertEquals(first, index.get(1))
        assertEquals(1, index.indexOfIds("10,20"))
        assertNull(index.indexOfIds("missing"))
    }

    @Test
    fun memePromptCacheRefreshesDirectoryListingAfterClear() {
        val directory = Files.createTempDirectory("jchatgpt-meme-prompt-test-")
        try {
            Files.createFile(directory.resolve("before.png"))
            val cache = MemePromptCache()

            assertContains(cache.get(directory.toString()), "before.png")

            Files.createFile(directory.resolve("after.png"))
            assertFalse(cache.get(directory.toString()).contains("after.png"))

            cache.clear()
            assertContains(cache.get(directory.toString()), "after.png")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun record(ids: String?, time: Int) = ChatMessageRecord(
        botId = 1,
        fromId = 2,
        targetId = 3,
        ids = ids,
        internalIds = null,
        time = time,
        kind = MessageSourceKind.GROUP,
        code = "[]",
    )
}
