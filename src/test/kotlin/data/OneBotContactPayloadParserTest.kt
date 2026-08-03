package top.jie65535.mirai.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OneBotContactPayloadParserTest {
    @Test
    fun parsesSuccessfulOneBotResponse() {
        val rows = OneBotContactPayloadParser.parseObjectList(
            "get_friend_list",
            """{"status":"ok","retcode":0,"data":[{"user_id":100,"nickname":"Alice"}]}""",
        )

        assertEquals(1, rows.size)
        assertEquals(100L, rows.single().long("user_id"))
        assertEquals("Alice", rows.single().text("nickname"))
    }

    @Test
    fun acceptsNumericFieldsEncodedAsStrings() {
        val rows = OneBotContactPayloadParser.parseObjectList(
            "get_group_member_list",
            """[{"user_id":"100","level":"12"}]""",
        )

        assertEquals(100L, rows.single().long("user_id"))
        assertEquals(12, rows.single().int("level"))
    }

    @Test
    fun rejectsFailedOneBotResponseWithoutLeakingPayload() {
        val failure = assertFailsWith<IllegalStateException> {
            OneBotContactPayloadParser.parseObjectList(
                "get_group_list",
                """{"status":"failed","retcode":1404,"message":"unsupported","data":null}""",
            )
        }

        assertEquals("OneBot get_group_list 调用失败: unsupported", failure.message)
    }
}
