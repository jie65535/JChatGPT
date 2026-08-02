package top.jie65535.mirai.conversation

import net.mamoe.mirai.message.data.MessageSourceKind
import top.jie65535.mirai.data.ChatMessageRecord
import kotlin.test.Test
import kotlin.test.assertEquals
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
