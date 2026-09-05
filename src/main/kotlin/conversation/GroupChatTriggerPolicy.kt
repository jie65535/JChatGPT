package top.jie65535.mirai.conversation

import kotlinx.coroutines.CancellationException

internal fun allowsGroupChatTrigger(
    requireOwnerInGroup: Boolean,
    ownerId: Long,
    isMember: (Long) -> Boolean,
    onFailure: (Exception) -> Unit,
): Boolean {
    if (!requireOwnerInGroup) return true
    if (ownerId <= 0) return false
    return try {
        isMember(ownerId)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        onFailure(cause)
        false
    }
}
