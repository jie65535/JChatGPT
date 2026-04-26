package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.*
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.nameCardOrNick
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.message.data.Image
import net.mamoe.mirai.message.data.Image.Key.queryUrl
import net.mamoe.mirai.message.data.SingleMessage
import net.mamoe.mirai.message.data.content
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.PluginConfig
import xyz.cssxsh.mirai.hibernate.MiraiHibernateRecorder
import xyz.cssxsh.mirai.hibernate.entry.MessageRecord
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class SearchChatHistory : BaseAgent(
    tool = Tool.function(
        name = "searchChatHistory",
        description = "搜索群聊消息历史，可按关键词、发送者、时间范围筛选。用于回溯之前的讨论、查找某人说过的话等。" +
                "不指定时间范围时默认搜索最近7天。指定时间时范围不能超过7天，如需更长跨度可分多次查询。" +
                "可以通过多轮搜索来实现找到某条消息的上下文。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("keyword") {
                    put("type", "string")
                    put("description", "消息内容关键词，人名请用sender")
                }
                putJsonObject("sender") {
                    put("type", "string")
                    put("description", "发送者名称或QQ号，查找某人的发言")
                }
                putJsonObject("startTime") {
                    put("type", "string")
                    put("description", "起始时间，格式：yyyy-MM-dd HH:mm，不填则默认为7天前")
                }
                putJsonObject("endTime") {
                    put("type", "string")
                    put("description", "结束时间，格式同上，不填则默认到当前时间")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "返回消息数量上限，默认20，最大50")
                }
            }
        }
    )
) {
    override val isEnabled: Boolean
        get() = JChatGPT.includeHistory

    override val loadingMessage: String
        get() = "搜索聊天记录中..."

    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        requireNotNull(args)

        val keyword = args["keyword"]?.jsonPrimitive?.contentOrNull
        val sender = args["sender"]?.jsonPrimitive?.contentOrNull

        val maxDays = PluginConfig.searchHistoryMaxDays
        val now = OffsetDateTime.now()

        val startTime = args["startTime"]?.jsonPrimitive?.contentOrNull?.let {
            parseTime(it) ?: return "startTime 格式错误，请使用 yyyy-MM-dd HH:mm"
        } ?: now.minusDays(maxDays.toLong())

        val endTime = args["endTime"]?.jsonPrimitive?.contentOrNull?.let {
            parseTime(it) ?: return "endTime 格式错误，请使用 yyyy-MM-dd HH:mm"
        } ?: now

        if (startTime >= endTime) {
            return "起始时间必须早于结束时间"
        }

        if (java.time.Duration.between(startTime, endTime).toDays() > maxDays) {
            return "搜索时间范围不能超过 ${maxDays}天，请缩小范围后重试"
        }

        val senderQq = resolveSenderQq(sender, event)
        val startEpoch = startTime.toEpochSecond().toInt()
        val endEpoch = endTime.toEpochSecond().toInt()
        val maxRecords = PluginConfig.searchHistoryMaxRecords

        val records = try {
            // 有 sender 时用 Member 重载，在数据库层过滤 fromId；否则用 Contact 重载
            if (senderQq != null && event is GroupMessageEvent) {
                val member = event.group[senderQq]
                if (member != null) {
                    MiraiHibernateRecorder[member, startEpoch, endEpoch]
                } else {
                    MiraiHibernateRecorder[event.subject, startEpoch, endEpoch]
                }
            } else {
                MiraiHibernateRecorder[event.subject, startEpoch, endEpoch]
            }.take(maxRecords).sortedBy { it.time }
        } catch (e: Throwable) {
            JChatGPT.logger.warning("查询消息历史失败", e)
            return "查询消息历史失败: ${e.message}"
        }

        var filtered = records

        // 消息内容在数据库中是序列化存储的，关键词只能在内存中过滤
        if (keyword != null) {
            filtered = filtered.filter {
                it.toMessageChain().content.contains(keyword, ignoreCase = true)
            }
        }

        if (filtered.isEmpty()) {
            return "未找到匹配的聊天记录"
        }

        val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 50) ?: 20
        val total = filtered.size
        val result = filtered.takeLast(limit)

        return buildString {
            appendLine("找到 $total 条匹配记录，显示最近 ${result.size} 条：")
            appendLine()
            appendHistory(this, result, event)
        }
    }

    /**
     * 将 sender 解析为 QQ 号，优先尝试纯数字，再尝试群成员名称匹配
     */
    private fun resolveSenderQq(sender: String?, event: MessageEvent): Long? {
        if (sender == null) return null
        sender.toLongOrNull()?.let { return it }
        if (event is GroupMessageEvent) {
            return event.group.members.firstOrNull {
                it.nameCardOrNick.contains(sender, ignoreCase = true)
            }?.id
        }
        return null
    }

    private suspend fun appendHistory(
        sb: StringBuilder,
        records: List<MessageRecord>,
        event: MessageEvent
    ) {
        val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        var lastFromId = 0L

        for (record in records) {
            val showSender = lastFromId != record.fromId
            if (showSender) {
                sb.appendLine()
                if (event is GroupMessageEvent) {
                    if (event.bot.id == record.fromId) {
                        sb.append("**你** ").append(event.bot.nameCardOrNick)
                    } else {
                        sb.append(getNameCard(event.group, record.fromId))
                    }
                }
                sb.append(" ")
                    .append(timeFormatter.format(
                        Instant.ofEpochSecond(record.time.toLong()).atZone(ZoneId.systemDefault())
                    ))
                    .append("：")
            }
            for (msg in record.toMessageChain()) {
                sb.append(singleMessageToText(msg))
            }
            sb.appendLine()
            lastFromId = record.fromId
        }
    }

    private suspend fun singleMessageToText(msg: SingleMessage): String {
        return when (msg) {
            is Image -> {
                try {
                    val url = msg.queryUrl()
                    "![${if (msg.isEmoji) "表情包" else "图片"}]($url)"
                } catch (_: Throwable) {
                    msg.content
                }
            }
            else -> msg.content
        }
    }

    private fun getNameCard(group: Group, qq: Long): String {
        val member = group[qq]
        return member?.nameCardOrNick ?: "未知群员($qq)"
    }

    companion object {
        private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun parseTime(text: String): OffsetDateTime? {
            return try {
                LocalDateTime.parse(text, timeFormatter)
                    .atZone(ZoneId.systemDefault())
                    .toOffsetDateTime()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
