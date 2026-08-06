package top.jie65535.mirai.data

import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.contact.Friend
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.Member
import net.mamoe.mirai.contact.Stranger
import net.mamoe.mirai.message.data.MessageSourceKind

data class ChatHistorySubject(
    val botId: Long,
    val kind: MessageSourceKind,
    val subjectId: Long,
) {
    companion object {
        fun from(contact: Contact): ChatHistorySubject = when (contact) {
            is Group -> ChatHistorySubject(contact.bot.id, MessageSourceKind.GROUP, contact.id)
            is Member -> ChatHistorySubject(contact.bot.id, MessageSourceKind.GROUP, contact.group.id)
            is Friend -> ChatHistorySubject(contact.bot.id, MessageSourceKind.FRIEND, contact.id)
            is Stranger -> ChatHistorySubject(contact.bot.id, MessageSourceKind.STRANGER, contact.id)
            else -> error("不支持查询的联系人 $contact")
        }
    }
}

enum class ChatHistoryMatchMode {
    ALL,
    ANY,
    PHRASE,
}

enum class ChatHistorySortOrder {
    NEWEST,
    OLDEST,
}

data class ChatHistoryCursor(
    val time: Int,
    val id: Long,
)

data class ChatHistorySearchRequest(
    val subject: ChatHistorySubject,
    val query: String? = null,
    val atTargetIds: Set<Long> = emptySet(),
    val matchMode: ChatHistoryMatchMode = ChatHistoryMatchMode.ALL,
    val fromId: Long? = null,
    val start: Int? = null,
    val end: Int? = null,
    val sortOrder: ChatHistorySortOrder = ChatHistorySortOrder.NEWEST,
    val limit: Int = 20,
    val cursor: ChatHistoryCursor? = null,
)

data class ChatHistorySearchPage(
    val records: List<ChatMessageRecord>,
    val totalMatches: Long?,
    val nextCursor: ChatHistoryCursor?,
)

data class ChatHistoryContext(
    val targetId: Long,
    val records: List<ChatMessageRecord>,
)

data class ChatHistorySenderAliasMatch(
    val userId: Long,
    val displayName: String,
    val matchRank: Int,
)
