package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.contact.nameCardOrNick
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.config.PluginConfig
import top.jie65535.mirai.data.ChatHistoryCursor
import top.jie65535.mirai.data.ChatHistoryMatchMode
import top.jie65535.mirai.data.ChatHistorySearchRequest
import top.jie65535.mirai.data.ChatHistorySortOrder
import top.jie65535.mirai.data.ChatHistoryStore
import top.jie65535.mirai.data.ChatHistorySubject
import top.jie65535.mirai.data.ContactNameMatch
import top.jie65535.mirai.data.ContactSnapshotStore
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Base64

class SearchChatHistory : BaseAgent(
    tool = Tool.function(
        name = "searchChatHistory",
        description = "在当前群聊或私聊的 SQLite 历史中搜索消息，可按内容、发送者和时间筛选。" +
                "默认返回最近匹配并在结果过多时提供 cursor；需要查看命中消息前后讨论时调用 getChatHistoryContext。" +
                "未指定 from 时默认搜索配置的近期天数，明确指定时间时可以搜索任意历史跨度。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "要搜索的消息文本；省略时按其他条件浏览时间线")
                }
                putJsonObject("senderId") {
                    put("type", "integer")
                    put("description", "发送者 QQ 号；仅在用户明确给出或上下文可靠提供 QQ 号时使用，不要根据昵称猜测")
                }
                putJsonObject("senderName") {
                    put("type", "string")
                    put("description", "用户用名称指代发送者时优先原样传入群名片、昵称或好友备注；可与 senderId 同时提供，没有 senderId 时解析发送者，有重名时返回候选 QQ 号")
                }
                putJsonObject("from") {
                    put("type", "string")
                    put("description", "起始时间，格式 yyyy-MM-dd HH:mm 或 yyyy-MM-dd")
                }
                putJsonObject("to") {
                    put("type", "string")
                    put("description", "结束时间，格式 yyyy-MM-dd HH:mm 或 yyyy-MM-dd；仅日期表示当天结束")
                }
                putJsonObject("cursor") {
                    put("type", "string")
                    put("description", "上一页返回的 nextCursor；继续同一搜索时原样传回")
                }
            }
        },
    )
) {
    override val isEnabled: Boolean
        get() = JChatGPT.includeHistory

    override val loadingMessage: String
        get() = "搜索聊天记录中..."

    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        val parameters = args ?: JsonObject(emptyMap())
        val query = parameters.string("query")?.trim()?.takeIf(String::isNotEmpty)
        val matchMode = ChatHistoryMatchMode.ALL
        val sortOrder = ChatHistorySortOrder.NEWEST
        val now = OffsetDateTime.now()
        val start = parameters.string("from")?.let {
            parseTime(it, endOfDay = false) ?: return "from 格式错误，请使用 yyyy-MM-dd HH:mm 或 yyyy-MM-dd"
        } ?: now.minusDays(PluginConfig.searchHistoryMaxDays.coerceAtLeast(1).toLong())
        val end = parameters.string("to")?.let {
            parseTime(it, endOfDay = true) ?: return "to 格式错误，请使用 yyyy-MM-dd HH:mm 或 yyyy-MM-dd"
        } ?: now
        if (start > end) return "起始时间必须早于或等于结束时间"

        val senderId = parameters["senderId"]?.jsonPrimitive?.longOrNull
        val senderName = parameters.string("senderName")?.trim()?.takeIf(String::isNotEmpty)
        val resolvedSenderId = senderId ?: senderName?.let { name ->
            when (val resolution = resolveSender(name, event)) {
                is SenderResolution.Found -> resolution.userId
                is SenderResolution.Ambiguous -> return buildString {
                    appendLine("找到多个名称匹配的发送者，请改用 senderId：")
                    resolution.candidates.forEach { candidate ->
                        appendLine("- ${candidate.displayName} (${candidate.userId})")
                    }
                }.trimEnd()
                SenderResolution.NotFound -> return "没有找到名称匹配的发送者：$name"
            }
        }

        val outputSafePageSize = ((PluginConfig.maxToolOutputLength.coerceAtLeast(1) - OUTPUT_RESERVE_CHARS)
            .coerceAtLeast(APPROXIMATE_RECORD_CHARS) / APPROXIMATE_RECORD_CHARS)
            .coerceIn(1, MAX_PAGE_SIZE)
        val configuredPageSize = minOf(
            PluginConfig.searchHistoryMaxRecords.coerceIn(1, MAX_PAGE_SIZE),
            outputSafePageSize,
        )
        val pageSize = DEFAULT_PAGE_SIZE.coerceAtMost(configuredPageSize)
        val cursor = parameters.string("cursor")?.let {
            decodeCursor(it, sortOrder) ?: return "cursor 无效，请重新开始搜索"
        }
        if (query != null && query.codePointCount(0, query.length) >= 3 &&
            !ChatHistoryStore.isSearchIndexAvailable
        ) {
            return "聊天记录全文索引当前不可用，无法执行关键词搜索"
        }

        val request = ChatHistorySearchRequest(
            subject = ChatHistorySubject.from(event.subject),
            query = query,
            matchMode = matchMode,
            fromId = resolvedSenderId,
            start = start.toEpochSecond().toInt(),
            end = end.toEpochSecond().toInt(),
            sortOrder = sortOrder,
            limit = pageSize,
            cursor = cursor,
        )
        val page = try {
            ChatHistoryStore.search(request)
        } catch (cause: Throwable) {
            JChatGPT.logger.warning("查询消息历史失败", cause)
            return "查询消息历史失败: ${cause.message}"
        }

        if (page.records.isEmpty()) {
            if (query != null && resolvedSenderId != null && cursor == null) {
                val withoutSender = runCatching {
                    ChatHistoryStore.search(
                        request.copy(
                            fromId = null,
                            limit = DIAGNOSTIC_PAGE_SIZE,
                            cursor = ChatHistoryCursor(request.end ?: Int.MAX_VALUE, Long.MAX_VALUE),
                        )
                    )
                }.getOrNull()
                if (withoutSender?.records?.isNotEmpty() == true) {
                    return buildString {
                        append("未找到发送者 $resolvedSenderId 的匹配记录。")
                        appendLine("去掉发送者筛选后找到了匹配消息，可能是 QQ 号不正确；示例：")
                        ChatHistoryToolFormatter.appendRecords(this, withoutSender.records, event, query)
                    }.trimEnd()
                }
            }
            return if (!ChatHistoryStore.isSearchIndexReady && query != null) {
                "未找到匹配的聊天记录。历史全文索引仍在后台构建，当前结果可能不完整。"
            } else if (resolvedSenderId != null) {
                "未找到发送者 $resolvedSenderId 在当前聊天和时间范围内的匹配记录"
            } else {
                "未找到匹配的聊天记录"
            }
        }

        return buildString {
            page.totalMatches?.let { total -> appendLine("找到 $total 条匹配记录，本页 ${page.records.size} 条：") }
                ?: appendLine("继续搜索，本页 ${page.records.size} 条：")
            page.nextCursor?.let { next ->
                appendLine("hasMore: true")
                appendLine("nextCursor: ${encodeCursor(next, sortOrder)}")
            } ?: appendLine("hasMore: false")
            appendLine()
            ChatHistoryToolFormatter.appendRecords(this, page.records, event, query)
            if (!ChatHistoryStore.isSearchIndexReady && query != null) {
                appendLine()
                append("[索引状态] 历史全文索引仍在后台构建，当前结果可能不完整。")
            }
        }.trimEnd()
    }

    private fun resolveSender(name: String, event: MessageEvent): SenderResolution {
        val groupId = (event as? GroupMessageEvent)?.group?.id
        val matches = runCatching {
            ContactSnapshotStore.findUsersByName(event.bot.id, groupId, name, limit = 6)
        }.getOrDefault(emptyList()).toMutableList()
        runCatching {
            ChatHistoryStore.findSenderAliases(ChatHistorySubject.from(event.subject), name, limit = 6)
        }.getOrDefault(emptyList()).forEach { alias ->
            if (matches.none { it.userId == alias.userId }) {
                matches += ContactNameMatch(alias.userId, alias.displayName, alias.matchRank)
            }
        }
        if (event is GroupMessageEvent) {
            event.group.members.asSequence()
                .filter { it.nameCardOrNick.contains(name, ignoreCase = true) }
                .forEach { member ->
                    if (matches.none { it.userId == member.id }) {
                        matches += ContactNameMatch(member.id, member.nameCardOrNick, 10)
                    }
                }
        }
        val sorted = matches.sortedWith(compareBy({ it.matchRank }, { it.userId }))
        val first = sorted.firstOrNull() ?: return SenderResolution.NotFound
        val second = sorted.getOrNull(1)
        return if (second == null || first.matchRank < second.matchRank) {
            SenderResolution.Found(first.userId)
        } else {
            SenderResolution.Ambiguous(sorted)
        }
    }

    private sealed interface SenderResolution {
        data class Found(val userId: Long) : SenderResolution
        data class Ambiguous(val candidates: List<ContactNameMatch>) : SenderResolution
        data object NotFound : SenderResolution
    }

    companion object {
        private const val DEFAULT_PAGE_SIZE = 20
        private const val DIAGNOSTIC_PAGE_SIZE = 6
        private const val MAX_PAGE_SIZE = 200
        private const val OUTPUT_RESERVE_CHARS = 1_000
        private const val APPROXIMATE_RECORD_CHARS = 450
        private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        fun parseTime(text: String, endOfDay: Boolean): OffsetDateTime? {
            val zone = ZoneId.systemDefault()
            return try {
                LocalDateTime.parse(text, dateTimeFormatter).atZone(zone).toOffsetDateTime()
            } catch (_: DateTimeParseException) {
                try {
                    val date = LocalDate.parse(text, dateFormatter)
                    val localDateTime = if (endOfDay) date.plusDays(1).atStartOfDay().minusNanos(1) else date.atStartOfDay()
                    localDateTime.atZone(zone).toOffsetDateTime()
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }

        private fun encodeCursor(cursor: ChatHistoryCursor, sortOrder: ChatHistorySortOrder): String {
            val raw = "${sortOrder.name}:${cursor.time}:${cursor.id}"
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
        }

        private fun decodeCursor(text: String, sortOrder: ChatHistorySortOrder): ChatHistoryCursor? {
            return runCatching {
                val raw = String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8)
                val parts = raw.split(':')
                if (parts.size != 3 || parts[0] != sortOrder.name) return null
                ChatHistoryCursor(parts[1].toInt(), parts[2].toLong())
            }.getOrNull()
        }

        private fun JsonObject.string(key: String): String? =
            get(key)?.jsonPrimitive?.contentOrNull
    }
}
