package top.jie65535.mirai.conversation

import net.mamoe.mirai.message.data.MessageSourceKind
import net.mamoe.mirai.message.data.At
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.NormalMember
import top.jie65535.mirai.data.ChatMessageRecord
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
    fun groupMentionsPreserveQqNumbersEvenWhenMembersShareAName() {
        val member = Proxy.newProxyInstance(NormalMember::class.java.classLoader, arrayOf(NormalMember::class.java)) { _, method, _ ->
            when (method.name) {
                "getNameCard" -> "同名群友"
                "getNick" -> "昵称"
                else -> error("Unexpected member call: ${method.name}")
            }
        } as NormalMember
        val group = Proxy.newProxyInstance(Group::class.java.classLoader, arrayOf(Group::class.java)) { _, method, args ->
            if (method.name == "get") member.takeIf { args!!.single() in listOf(123L, 456L) }
            else error("Unexpected group call: ${method.name}")
        } as Group
        val first = ConversationContext.formatMention(At(123), group)
        val second = ConversationContext.formatMention(At(456), group)
        assertEquals("@同名群友(qq=123)", first)
        assertEquals("@同名群友(qq=456)", second)
        assertNotEquals(first, second)
        assertEquals("@789", ConversationContext.formatMention(At(789), group))
        assertEquals("@123", ConversationContext.formatMention(At(123), null))
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
