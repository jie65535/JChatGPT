package top.jie65535.mirai.conversation

import com.aallam.openai.api.chat.ChatMessage
import kotlinx.coroutines.runBlocking
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.Member
import net.mamoe.mirai.contact.MemberPermission.ADMINISTRATOR
import net.mamoe.mirai.contact.MemberPermission.MEMBER
import net.mamoe.mirai.contact.MemberPermission.OWNER
import net.mamoe.mirai.contact.nameCardOrNick
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.message.data.At
import net.mamoe.mirai.message.data.ForwardMessage
import net.mamoe.mirai.message.data.Image
import net.mamoe.mirai.message.data.Image.Key.queryUrl
import net.mamoe.mirai.message.data.Message
import net.mamoe.mirai.message.data.MessageChain
import net.mamoe.mirai.message.data.MessageSource
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.message.data.QuoteReply
import net.mamoe.mirai.message.data.SingleMessage
import net.mamoe.mirai.message.data.buildMessageChain
import net.mamoe.mirai.message.data.content
import net.mamoe.mirai.message.data.ids
import net.mamoe.mirai.message.data.source
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.config.PluginConfig
import top.jie65535.mirai.data.ChatHistoryStore
import top.jie65535.mirai.data.ChatMessageRecord
import top.jie65535.mirai.data.ContactSnapshotStore
import top.jie65535.mirai.data.PluginData
import top.jie65535.mirai.data.SkillStore
import top.jie65535.mirai.llm.LargeLanguageModels
import top.jie65535.mirai.media.ImageIndex
import top.jie65535.mirai.profile.UserProfileContextRenderer
import top.jie65535.mirai.profile.UserProfileStore
import util.LunarDateUtil
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal data class ConversationCache(
    val history: MutableList<ChatMessage>,
    val lastActivityAt: Int,
    val replyIndex: ReplyIndex,
    val imageIndex: ImageIndex,
) {
    fun isExpired(ttlSeconds: Int): Boolean =
        OffsetDateTime.now().toEpochSecond().toInt() - lastActivityAt > ttlSeconds
}

internal class ReplyIndex {
    private val byIndex = LinkedHashMap<Int, ChatMessageRecord>()
    private val indexByIds = HashMap<String, Int>()
    private var counter = 0

    fun add(record: ChatMessageRecord): Int {
        record.ids?.let { ids -> indexByIds[ids]?.let { return it } }
        val index = ++counter
        byIndex[index] = record
        record.ids?.let { indexByIds[it] = index }
        return index
    }

    fun get(index: Int): ChatMessageRecord? = byIndex[index]

    fun indexOfIds(ids: String): Int? = indexByIds[ids]
}

internal object ConversationContext {
    private val chronologicalRecordOrder = compareBy<ChatMessageRecord> { it.time }
        .thenBy { if (it.id == 0L) Long.MAX_VALUE else it.id }
    private val contextCache = mutableMapOf<Long, ConversationCache>()
    private val replyIndexes = mutableMapOf<Long, ReplyIndex>()
    private val imageIndexes = mutableMapOf<Long, ImageIndex>()
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年MM月dd E HH:mm:ss")
    private val shortTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneOffset.systemDefault())
    private var memePrompt: String? = null

    fun clearCache() {
        synchronized(contextCache) { contextCache.clear() }
    }

    fun clearAll() {
        synchronized(contextCache) { contextCache.clear() }
        synchronized(replyIndexes) { replyIndexes.clear() }
        synchronized(imageIndexes) { imageIndexes.clear() }
    }

    fun cache(subjectId: Long): ConversationCache? = synchronized(contextCache) {
        contextCache[subjectId]
    }

    fun saveCache(subjectId: Long, cache: ConversationCache) {
        synchronized(contextCache) { contextCache[subjectId] = cache }
    }

    fun activateReplyIndex(subjectId: Long, cached: ReplyIndex?): ReplyIndex =
        (cached ?: ReplyIndex()).also { index ->
            synchronized(replyIndexes) { replyIndexes[subjectId] = index }
        }

    fun activateImageIndex(subjectId: Long, cached: ImageIndex?): ImageIndex =
        (cached ?: ImageIndex()).also { index ->
            synchronized(imageIndexes) { imageIndexes[subjectId] = index }
        }

    fun releaseActiveIndexes(subjectId: Long) {
        synchronized(replyIndexes) { replyIndexes.remove(subjectId) }
        synchronized(imageIndexes) { imageIndexes.remove(subjectId) }
    }

    fun lookupReplyTarget(subjectId: Long, index: Int): ChatMessageRecord? =
        synchronized(replyIndexes) { replyIndexes[subjectId]?.get(index) }

    fun registerImage(subjectId: Long, imageId: String, imageUrl: String): Int? =
        synchronized(imageIndexes) { imageIndexes[subjectId]?.add(imageId, imageUrl) }

    fun lookupImageUrl(subjectId: Long, index: Int): String? =
        synchronized(imageIndexes) { imageIndexes[subjectId]?.getUrl(index) }

    fun getSystemPrompt(event: MessageEvent): String {
        val now = OffsetDateTime.now()
        val prompt = StringBuilder(LargeLanguageModels.systemPrompt)
        fun replace(target: String, replacement: () -> String) {
            val index = prompt.indexOf(target)
            if (index != -1) prompt.replace(index, index + target.length, replacement())
        }

        replace("{time}") {
            val solarTime = dateTimeFormatter.format(now)
            "$solarTime\n农历${LunarDateUtil.getFormattedLunarAndHoliday(now)}"
        }
        replace("{subject}") {
            if (event is GroupMessageEvent) {
                "\"${event.subject.name}\" 群聊中，你在本群的名片是：${getNameCard(event.subject.botAsMember)}"
            } else {
                "与 \"${event.senderName}\" 私聊中"
            }
        }
        replace("{memory}") {
            PluginData.contactMemory[event.subject.id].orEmpty().ifEmpty { "暂无相关记忆" }
        }
        replace("{skills}") {
            if (PluginConfig.skillsEnabled) SkillStore.buildIndexPrompt() else "暂无技能"
        }
        replace("{meme}") { buildMemePrompt() }
        return prompt.toString()
    }

    fun getHistory(event: MessageEvent): String {
        val imageIndex = activeImageIndex(event.subject.id)
        if (!JChatGPT.includeHistory) {
            return formatRecordContent(event.message, event.subject, imageIndex)
        }
        val beforeTimestamp = OffsetDateTime.now()
            .minusMinutes(PluginConfig.historyWindowMin.toLong())
            .toEpochSecond()
            .toInt()
        return getAfterHistory(beforeTimestamp, event, PluginConfig.historyMessageLimit)
    }

    fun getAfterHistory(time: Int, event: MessageEvent, limit: Int? = null): String {
        if (!JChatGPT.includeHistory) return ""
        val history = try {
            ChatHistoryStore.query(
                contact = event.subject,
                start = time,
                end = OffsetDateTime.now().toEpochSecond().toInt(),
                limit = limit,
            ).sortedWith(chronologicalRecordOrder).toMutableList()
        } catch (cause: Throwable) {
            JChatGPT.logger.warning("查询 SQLite 消息历史失败", cause)
            mutableListOf()
        }

        val messageIds = event.message.ids.joinToString(",")
        if (history.none { it.ids == messageIds }) {
            history += ChatMessageRecord.fromSuccess(event.message.source, event.message)
            history.sortWith(chronologicalRecordOrder)
        }

        val result = StringBuilder()
        var lastUserId = 0L
        var lastTime = 0L
        val replyIndex = activeReplyIndex(event.subject.id)
        val imageIndex = activeImageIndex(event.subject.id)
        if (event is GroupMessageEvent) {
            appendUserProfileContext(result, history, event)
            result.appendLine("## 近期群消息（更早已隐藏，行首[n]为消息编号；正文[图片n]/[表情包n]中的n为识图或图片编辑编号）")
            history.forEach { record ->
                val showSender = lastUserId != record.fromId
                val showTime = showSender || record.time.toLong() - lastTime > CONTINUATION_TIME_GAP_SECONDS
                appendGroupMessageRecord(result, record, event, replyIndex, imageIndex, showSender, showTime)
                lastUserId = record.fromId
                lastTime = record.time.toLong()
            }
        } else {
            appendPrivateUserContext(result, event)
            result.appendLine("## 近期对话（更早已隐藏，行首[n]为消息编号；正文[图片n]/[表情包n]中的n为识图或图片编辑编号）")
            history.forEach { record ->
                val showSender = lastUserId != record.fromId
                val showTime = showSender || record.time.toLong() - lastTime > CONTINUATION_TIME_GAP_SECONDS
                appendMessageRecord(result, record, event, replyIndex, imageIndex, showSender, showTime)
                lastUserId = record.fromId
                lastTime = record.time.toLong()
            }
        }
        return result.toString()
    }

    fun toMessage(contact: Contact, content: String): Message {
        if (content.isEmpty()) return PlainText("...")
        if (content.length < 3) return PlainText(content)

        val chunks = mutableListOf<MessageChunk>()
        REGEX_AT_QQ.findAll(content).forEach { match ->
            val qq = match.groups[1]?.value?.toLongOrNull()
            if (qq != null && contact is Group) {
                contact[qq]?.let { chunks += MessageChunk(match.range, At(it)) }
            }
        }
        REGEX_IMAGE.findAll(content).forEach { match ->
            chunks += MessageChunk(match.range, Image(match.groupValues[2]))
        }
        return buildMessageChain {
            var index = 0
            chunks.sortedBy { it.range.first }.forEach { (range, message) ->
                if (index < range.first) append(content, index, range.first)
                append(message)
                index = range.last + 1
            }
            if (index < content.length) append(content, index, content.length)
        }
    }

    private fun activeReplyIndex(subjectId: Long): ReplyIndex = synchronized(replyIndexes) {
        replyIndexes.getOrPut(subjectId) { ReplyIndex() }
    }

    private fun activeImageIndex(subjectId: Long): ImageIndex = synchronized(imageIndexes) {
        imageIndexes.getOrPut(subjectId) { ImageIndex() }
    }

    private fun buildMemePrompt(): String {
        memePrompt?.let { return it }
        if (PluginConfig.memeDir.isEmpty()) return ""
        return buildString {
            val directory = File(PluginConfig.memeDir)
            if (!directory.isDirectory) {
                append("配置的meme路径不存在！")
                return@buildString
            }
            append("memes文件夹地址为：").appendLine(PluginConfig.memeDir)
            val memes = directory.list().orEmpty()
            if (memes.isEmpty()) {
                append("暂无表情包~")
            } else {
                memes.forEach { append("- ").appendLine(it) }
                appendLine()
                append("表情包示例：![").append(memes[0]).append("](")
                    .append(File(directory, memes[0]).absoluteFile).appendLine(")")
            }
        }.also { memePrompt = it }
    }

    private fun appendUserProfileContext(
        target: StringBuilder,
        history: List<ChatMessageRecord>,
        event: GroupMessageEvent,
    ) {
        if (!PluginConfig.profileAutoInjectEnabled && !PluginConfig.enableFavorabilitySystem) return
        val candidateIds = buildList {
            add(event.sender.id)
            history.asReversed().forEach { add(it.fromId) }
        }.asSequence()
            .filter { it != event.bot.id }
            .distinct()
            .take(PluginConfig.profileAutoInjectMaxUsers.coerceIn(1, 10))
            .toList()
        val profiles = if (PluginConfig.profileEnabled && PluginConfig.profileAutoInjectEnabled &&
            UserProfileStore.isAvailable
        ) {
            candidateIds.mapNotNull { userId ->
                runCatching { UserProfileStore.load(userId) }
                    .onFailure { JChatGPT.logger.warning("读取用户画像失败: user=$userId", it) }
                    .getOrNull()
            }
        } else emptyList()
        val favorability = if (PluginConfig.enableFavorabilitySystem) {
            candidateIds.mapNotNull { id -> PluginData.userFavorability[id]?.let { id to it } }.toMap()
        } else emptyMap()
        val snapshotNames = runCatching {
            ContactSnapshotStore.loadDisplayNames(event.bot.id, event.group.id, candidateIds)
        }.getOrDefault(emptyMap())
        val names = candidateIds.associateWith { id ->
            event.group[id]?.nameCardOrNick ?: snapshotNames[id] ?: id.toString()
        }
        target.append(
            UserProfileContextRenderer.render(
                profiles = profiles,
                favorabilityByUserId = favorability,
                displayNames = names,
                activeUserIds = candidateIds.toSet(),
                summaryMaxChars = PluginConfig.profileAutoInjectSummaryMaxChars,
            )
        )
    }

    private fun appendPrivateUserContext(target: StringBuilder, event: MessageEvent) {
        if (!PluginConfig.profileAutoInjectEnabled && !PluginConfig.enableFavorabilitySystem) return
        val userId = event.sender.id
        val profiles = if (PluginConfig.profileEnabled && PluginConfig.profileAutoInjectEnabled &&
            UserProfileStore.isAvailable
        ) {
            listOfNotNull(
                runCatching { UserProfileStore.load(userId) }
                    .onFailure { JChatGPT.logger.warning("读取用户画像失败: user=$userId", it) }
                    .getOrNull()
            )
        } else emptyList()
        val favorability = if (PluginConfig.enableFavorabilitySystem) {
            PluginData.userFavorability[userId]?.let { mapOf(userId to it) }.orEmpty()
        } else emptyMap()
        val snapshotName = runCatching {
            ContactSnapshotStore.loadDisplayName(event.bot.id, null, userId)
        }.getOrNull()
        target.append(
            UserProfileContextRenderer.render(
                profiles = profiles,
                favorabilityByUserId = favorability,
                displayNames = mapOf(userId to (snapshotName ?: event.senderName)),
                activeUserIds = setOf(userId),
                summaryMaxChars = PluginConfig.profileAutoInjectSummaryMaxChars,
                sectionTitle = "你对对方的认识",
            )
        )
    }

    private fun appendGroupMessageRecord(
        target: StringBuilder,
        record: ChatMessageRecord,
        event: GroupMessageEvent,
        replyIndex: ReplyIndex,
        imageIndex: ImageIndex,
        showSender: Boolean,
        showTime: Boolean,
    ) {
        val chain = record.toMessageChain()
        target.append('[').append(replyIndex.add(record)).append("] ")
        if (showSender) {
            if (event.bot.id == record.fromId) {
                target.append("**你** ").append(getNameCard(event.subject.botAsMember))
            } else {
                target.append(getNameCard(event.subject, record.fromId))
            }
            target.append(' ').append(shortTime(record.time)).append(' ')
        } else {
            target.append(" └ ")
            if (showTime) target.append(shortTime(record.time)).append(' ')
        }
        chain[QuoteReply.Key]?.let { appendQuoteMarker(target, it, event.subject, replyIndex, imageIndex) }
        target.appendLine(formatRecordContent(chain, event.subject, imageIndex))
    }

    private fun appendMessageRecord(
        target: StringBuilder,
        record: ChatMessageRecord,
        event: MessageEvent,
        replyIndex: ReplyIndex,
        imageIndex: ImageIndex,
        showSender: Boolean,
        showTime: Boolean,
    ) {
        val chain = record.toMessageChain()
        target.append('[').append(replyIndex.add(record)).append("] ")
        if (showSender) {
            if (event.bot.id == record.fromId) target.append("**你** ").append(event.bot.nameCardOrNick)
            else target.append(event.senderName)
            target.append(' ').append(shortTime(record.time)).append(' ')
        } else {
            target.append(" └ ")
            if (showTime) target.append(shortTime(record.time)).append(' ')
        }
        chain[QuoteReply.Key]?.let { appendQuoteMarker(target, it, event.subject, replyIndex, imageIndex) }
        target.appendLine(formatRecordContent(chain, event.subject, imageIndex))
    }

    private fun appendQuoteMarker(
        target: StringBuilder,
        quote: QuoteReply,
        contact: Contact,
        replyIndex: ReplyIndex,
        imageIndex: ImageIndex,
    ) {
        replyIndex.indexOfIds(quote.source.ids.joinToString(","))?.let { index ->
            target.append("↩[").append(index).append("] ")
            return
        }
        val author = if (contact is Group) {
            contact[quote.source.fromId]?.nameCardOrNick ?: "未知(${quote.source.fromId})"
        } else quote.source.fromId.toString()
        val snippet = quote.source.originalMessage
            .joinToString("") { singleMessageToText(it, imageIndex) }
            .replace("\n", " ")
            .let { if (it.length > 20) it.take(20) + "…" else it }
        target.append("↩(").append(author).append(":\"").append(snippet).append("\") ")
    }

    private fun formatRecordContent(
        chain: MessageChain,
        contact: Contact,
        imageIndex: ImageIndex,
    ): String = chain.asSequence()
        .filterNot { it is QuoteReply || it is MessageSource }
        .joinToString("") { message ->
            when (message) {
                is At -> if (contact is Group) message.getDisplay(contact) else message.content
                else -> singleMessageToText(message, imageIndex)
            }
        }

    private fun singleMessageToText(message: SingleMessage, imageIndex: ImageIndex): String = when (message) {
        is ForwardMessage -> formatForward(message, 1, imageIndex)
        is Image -> try {
            val url = runBlocking { message.queryUrl() }
            val index = imageIndex.add(message.imageId, url)
            "[${if (message.isEmoji) "表情包" else "图片"}$index]"
        } catch (cause: Throwable) {
            JChatGPT.logger.warning("图片地址获取失败", cause)
            message.content
        }
        else -> message.content
    }

    private fun formatForward(forward: ForwardMessage, depth: Int, imageIndex: ImageIndex): String = buildString {
        val quote = ">".repeat(depth) + " "
        append("[转发消息·").append(forward.nodeList.size).append("条")
        if (forward.title.isNotEmpty()) append(':').append(forward.title)
        append(']')
        forward.nodeList.forEach { node ->
            append('\n').append(quote).append(node.senderName).append(' ')
                .append(shortTimeFormatter.format(Instant.ofEpochSecond(node.time.toLong())))
                .append(": ")
            node.messageChain.forEach { child ->
                if (child is ForwardMessage) append(formatForward(child, depth + 1, imageIndex))
                else append(singleMessageToText(child, imageIndex).replace("\n", "\n$quote"))
            }
        }
    }

    private fun getNameCard(group: Group, qq: Long): String =
        group[qq]?.let(::getNameCard) ?: "未知群员($qq)"

    private fun getNameCard(member: Member): String {
        val result = StringBuilder("【")
        try {
            result.append("lv").append(member.active.temperature).append(' ')
        } catch (cause: Throwable) {
            JChatGPT.logger.warning("获取群活跃等级失败", cause)
        }
        result.append(
            when (member.permission) {
                OWNER -> "群主"
                ADMINISTRATOR -> "管理员"
                MEMBER -> "群员"
            }
        )
        try {
            if (member.specialTitle.isNotEmpty()) result.append(" 头衔\"").append(member.specialTitle).append('"')
            else if (member.temperatureTitle.isNotEmpty()) result.append(' ').append(member.temperatureTitle)
        } catch (cause: Throwable) {
            JChatGPT.logger.warning("获取群头衔失败", cause)
        }
        return result.append("】\t\"").append(member.nameCardOrNick)
            .append("\"\t(qq=").append(member.id).append(')').toString()
    }

    private fun shortTime(epochSecond: Int): String =
        shortTimeFormatter.format(Instant.ofEpochSecond(epochSecond.toLong()))

    private data class MessageChunk(val range: IntRange, val content: Message)

    private const val CONTINUATION_TIME_GAP_SECONDS = 60L
    private val REGEX_AT_QQ = Regex("""@(\d{5,12})""")
    private val REGEX_IMAGE = Regex("""!\[(.*?)]\(([^\s"']+).*?\)""")
}
