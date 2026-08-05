package top.jie65535.mirai.conversation

import net.mamoe.mirai.message.data.MessageSourceKind

internal data class ConversationKey(
    val botId: Long,
    val kind: MessageSourceKind,
    val subjectId: Long,
)
