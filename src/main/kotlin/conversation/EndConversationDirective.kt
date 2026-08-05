package top.jie65535.mirai.conversation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal const val END_CONVERSATION_TOOL_NAME = "endConversation"
internal const val FOLLOW_UP_WAIT_ARGUMENT = "waitForFollowUp"

internal data class FollowUpWaitDirective(
    val timeoutSeconds: Int,
    val fromUserIds: Set<Long>,
    val condition: String,
)

internal fun parseFollowUpWait(arguments: JsonObject?): FollowUpWaitDirective? {
    val wait = arguments?.get(FOLLOW_UP_WAIT_ARGUMENT) as? JsonObject ?: return null
    val timeoutSeconds = (wait["timeoutSeconds"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_WAIT_SECONDS
    if (timeoutSeconds !in MIN_WAIT_SECONDS..MAX_WAIT_SECONDS) return null

    val userIdsJson = wait["fromUserIds"] as? JsonArray ?: return null
    if (userIdsJson.size !in 1..MAX_WAIT_USERS) return null
    val userIds = LinkedHashSet<Long>(userIdsJson.size)
    for (element in userIdsJson) {
        val userId = (element as? JsonPrimitive)?.longOrNull ?: return null
        if (userId <= 0 || !userIds.add(userId)) return null
    }

    val condition = (wait["condition"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (condition.isEmpty() || condition.length > MAX_WAIT_CONDITION_LENGTH) return null

    return FollowUpWaitDirective(
        timeoutSeconds = timeoutSeconds,
        fromUserIds = userIds,
        condition = condition,
    )
}

private const val DEFAULT_WAIT_SECONDS = 30
private const val MIN_WAIT_SECONDS = 5
private const val MAX_WAIT_SECONDS = 120
private const val MAX_WAIT_USERS = 10
private const val MAX_WAIT_CONDITION_LENGTH = 200
