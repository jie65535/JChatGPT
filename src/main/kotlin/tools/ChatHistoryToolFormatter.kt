package top.jie65535.mirai.tools

import net.mamoe.mirai.contact.nameCardOrNick
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageEvent
import top.jie65535.mirai.data.ChatHistorySearchText
import top.jie65535.mirai.data.ChatMessageRecord
import top.jie65535.mirai.data.ContactSnapshotStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object ChatHistoryToolFormatter {
    private const val SNIPPET_LENGTH = 360
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun appendRecords(
        output: StringBuilder,
        records: List<ChatMessageRecord>,
        event: MessageEvent,
        query: String? = null,
        targetId: Long? = null,
    ) {
        val group = (event as? GroupMessageEvent)?.group
        val userIds = records.map(ChatMessageRecord::fromId).distinct()
        val snapshotNames = runCatching {
            ContactSnapshotStore.loadDisplayNames(event.bot.id, group?.id, userIds)
        }.getOrDefault(emptyMap())
        val atNames = if (group != null) {
            val targetIds = records.asSequence()
                .flatMap { ChatHistorySearchText.extractAtTargets(it.code).asSequence() }
                .toSet()
            runCatching {
                ContactSnapshotStore.loadDisplayNames(event.bot.id, group.id, targetIds)
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        records.forEach { record ->
            val marker = if (record.id == targetId) ">>> " else ""
            val sender = when {
                record.fromId == event.bot.id -> "你"
                group != null -> group[record.fromId]?.nameCardOrNick
                    ?: snapshotNames[record.fromId]
                    ?: "未知群员"
                record.fromId == event.sender.id -> event.senderName
                else -> snapshotNames[record.fromId] ?: "用户"
            }
            val time = timeFormatter.format(
                Instant.ofEpochSecond(record.time.toLong()).atZone(ZoneId.systemDefault())
            )
            val content = buildSnippet(ChatHistorySearchText.extract(record.code, atNames), query)
            output.append(marker)
                .append("[messageId=").append(record.id).append("] ")
                .append(time).append(' ')
                .append(sender).append('(').append(record.fromId).append("): ")
                .appendLine(content.ifEmpty { "[无文本消息]" })
        }
    }

    private fun buildSnippet(content: String, query: String?): String {
        if (content.length <= SNIPPET_LENGTH) return content
        val terms = query.orEmpty().split(Regex("\\s+")).filter(String::isNotBlank)
        val matchIndex = terms.mapNotNull { term ->
            content.indexOf(term, ignoreCase = true).takeIf { it >= 0 }
        }.minOrNull() ?: 0
        val start = (matchIndex - SNIPPET_LENGTH / 3).coerceAtLeast(0)
        val end = (start + SNIPPET_LENGTH).coerceAtMost(content.length)
        return buildString {
            if (start > 0) append("...")
            append(content, start, end)
            if (end < content.length) append("...")
        }
    }
}
