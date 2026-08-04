package top.jie65535.mirai.profile

import net.mamoe.mirai.message.data.MessageSourceKind
import top.jie65535.mirai.data.ChatMessageRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileMessageRendererTest {
    @Test
    fun rendersStoredJsonWithoutMiraiRuntime() {
        val record = record(
            """
            [
              {"type":"QuoteReply","source":{"fromId":200,"originalMessage":[{"type":"PlainText","content":"我在公明工作"}]}},
              {"type":"At","target":200},
              {"type":"PlainText","content":" 这说的是你，不是我"},
              {"type":"Image","isEmoji":false}
            ]
            """.trimIndent()
        )

        assertEquals(
            "[引用 U1: 我在公明工作]@U1 这说的是你，不是我[图片]",
            ProfileMessageRenderer.render(record, mapOf(100L to "TARGET", 200L to "U1"), 500),
        )
        assertEquals(setOf(200L), ProfileMessageRenderer.referencedUserIds(record))
    }

    @Test
    fun truncatesLongMessagesAfterRendering() {
        val record = record("""[{"type":"PlainText","content":"1234567890"}]""")

        assertEquals("12345678...[截断]", ProfileMessageRenderer.render(record, emptyMap(), 8))
    }

    @Test
    fun doesNotSplitSurrogatePairsAtTruncationBoundaries() {
        val emoji = "\uD83D\uDE00"
        val finalText = "a".repeat(7) + emoji + "tail"
        assertEquals(
            "a".repeat(7) + "...[截断]",
            ProfileMessageRenderer.render(
                record("""[{"type":"PlainText","content":"$finalText"}]"""),
                emptyMap(),
                8,
            ),
        )

        val quotedText = "q".repeat(159) + emoji + "tail"
        assertEquals(
            "[引用 U1: ${"q".repeat(159)}]",
            ProfileMessageRenderer.render(
                record(
                    """
                    [{"type":"QuoteReply","source":{"fromId":200,"originalMessage":[
                      {"type":"PlainText","content":"$quotedText"}
                    ]}}]
                    """.trimIndent()
                ),
                mapOf(200L to "U1"),
                1_000,
            ),
        )

        val forwardedText = "f".repeat(199) + emoji + "tail"
        assertEquals(
            "[转发消息] sender: ${"f".repeat(199)}",
            ProfileMessageRenderer.render(
                record(
                    """
                    [{"type":"ForwardMessage","nodeList":[{
                      "senderName":"sender",
                      "messageChain":[{"type":"PlainText","content":"$forwardedText"}]
                    }]}]
                    """.trimIndent()
                ),
                emptyMap(),
                1_000,
            ),
        )
    }

    @Test
    fun replacesUnpairedSurrogatesBeforeBuildingModelPrompts() {
        val loneHighSurrogate = "\uD83D"

        assertEquals(
            "prefix\uFFFDsuffix",
            ProfileMessageRenderer.render(
                record("""[{"type":"PlainText","content":"prefix${loneHighSurrogate}suffix"}]"""),
                emptyMap(),
                1_000,
            ),
        )
    }

    private fun record(code: String) = ChatMessageRecord(
        botId = 1,
        fromId = 100,
        targetId = 10,
        ids = null,
        internalIds = null,
        time = 100,
        kind = MessageSourceKind.GROUP,
        code = code,
    )
}
