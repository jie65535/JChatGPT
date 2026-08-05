package top.jie65535.mirai.conversation

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EndConversationDirectiveTest {
    @Test
    fun emptyEndConversationLeavesImmediately() {
        assertNull(parseFollowUpWait(buildJsonObject {}))
    }

    @Test
    fun parsesTargetedOneShotWait() {
        val directive = parseFollowUpWait(arguments(timeoutSeconds = 45))

        assertEquals(
            FollowUpWaitDirective(
                timeoutSeconds = 45,
                fromUserIds = linkedSetOf(123L, 456L),
                condition = "等待对方补充版本信息",
            ),
            directive,
        )
    }

    @Test
    fun appliesDefaultTimeout() {
        assertEquals(30, parseFollowUpWait(arguments(timeoutSeconds = null))?.timeoutSeconds)
    }

    @Test
    fun rejectsWaitWithoutTargetsOrConcreteCondition() {
        assertNull(
            parseFollowUpWait(
                buildJsonObject {
                    putJsonObject(FOLLOW_UP_WAIT_ARGUMENT) {
                        putJsonArray("fromUserIds") {}
                        put("condition", "等待回复")
                    }
                }
            )
        )
        assertNull(
            parseFollowUpWait(
                buildJsonObject {
                    putJsonObject(FOLLOW_UP_WAIT_ARGUMENT) {
                        putJsonArray("fromUserIds") { add(123) }
                        put("condition", " ")
                    }
                }
            )
        )
    }

    @Test
    fun rejectsOutOfRangeTimeoutAndDuplicateTargets() {
        assertNull(parseFollowUpWait(arguments(timeoutSeconds = 121)))
        assertNull(
            parseFollowUpWait(
                buildJsonObject {
                    putJsonObject(FOLLOW_UP_WAIT_ARGUMENT) {
                        put("timeoutSeconds", 30)
                        putJsonArray("fromUserIds") {
                            add(123)
                            add(123)
                        }
                        put("condition", "等待对方补充版本信息")
                    }
                }
            )
        )
    }

    private fun arguments(timeoutSeconds: Int?) = buildJsonObject {
        putJsonObject(FOLLOW_UP_WAIT_ARGUMENT) {
            if (timeoutSeconds != null) put("timeoutSeconds", timeoutSeconds)
            putJsonArray("fromUserIds") {
                add(123)
                add(456)
            }
            put("condition", "等待对方补充版本信息")
        }
    }
}
