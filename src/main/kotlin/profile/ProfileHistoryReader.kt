package top.jie65535.mirai.profile

import net.mamoe.mirai.message.data.MessageSourceKind
import org.sqlite.SQLiteConfig
import top.jie65535.mirai.data.ChatMessageRecord
import top.jie65535.mirai.data.ContactProfileHint
import top.jie65535.mirai.data.ContactSnapshotStore
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

internal object ProfileConversationWindowDefaults {
    const val IDLE_GAP_SECONDS = 20 * 60
    const val TARGET_CONTENT_CHARS = 30_000
    const val MAX_MESSAGES = 800
    const val MAX_CONTENT_CHARS = 70_000
    const val MAX_PACKED_SPAN_SECONDS = 24 * 60 * 60
}

class ProfileHistoryReader(private val databaseFile: File) {
    data class TimeBounds(val startTime: Int, val endTime: Int)
    data class GroupTimeBounds(
        val botId: Long,
        val groupId: Long,
        val startTime: Int,
        val endTime: Int,
    )

    private data class Episode(
        val index: Int,
        val groupId: Long,
        val targetMessages: List<ChatMessageRecord>,
    )

    init {
        require(databaseFile.isFile) { "聊天历史数据库不存在: ${databaseFile.absolutePath}" }
        Class.forName("org.sqlite.JDBC")
    }

    fun findUserTimeBounds(userId: Long): TimeBounds? = openReadConnection().use { connection ->
        connection.prepareStatement(
            """
            SELECT MIN(time) AS min_time, MAX(time) AS max_time
            FROM message_record
            WHERE kind = ? AND recalled = 0 AND from_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, MessageSourceKind.GROUP.ordinal)
            statement.setLong(2, userId)
            statement.executeQuery().use { results ->
                if (!results.next()) return@use null
                val start = results.getInt("min_time")
                if (results.wasNull()) return@use null
                val max = results.getInt("max_time")
                TimeBounds(start, max.safeNextSecond())
            }
        }
    }

    fun findGroupTimeBounds(groupId: Long): GroupTimeBounds? = openReadConnection().use { connection ->
        connection.prepareStatement(
            """
            SELECT bot_id, MIN(time) AS min_time, MAX(time) AS max_time
            FROM message_record
            WHERE kind = ? AND recalled = 0 AND target_id = ?
            GROUP BY bot_id
            ORDER BY max_time DESC, bot_id ASC
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, MessageSourceKind.GROUP.ordinal)
            statement.setLong(2, groupId)
            statement.executeQuery().use { results ->
                if (!results.next()) return@use null
                GroupTimeBounds(
                    botId = results.getLong("bot_id"),
                    groupId = groupId,
                    startTime = results.getInt("min_time"),
                    endTime = results.getInt("max_time").safeNextSecond(),
                )
            }
        }
    }

    fun listGroupTimeBounds(): List<GroupTimeBounds> = openReadConnection().use { connection ->
        val pairs = connection.prepareStatement(
            """
            SELECT bot_id, target_id
            FROM message_record
            WHERE kind = ? AND target_id > 0
            GROUP BY bot_id, target_id
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, MessageSourceKind.GROUP.ordinal)
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) {
                        add(results.getLong("bot_id") to results.getLong("target_id"))
                    }
                }
            }
        }

        val selectedByGroup = hashMapOf<Long, GroupTimeBounds>()
        connection.prepareStatement(GROUP_ENDPOINT_TIME_SQL.format("ASC", "ASC")).use { earliest ->
            connection.prepareStatement(GROUP_ENDPOINT_TIME_SQL.format("DESC", "DESC")).use { latest ->
                pairs.forEach { (botId, groupId) ->
                    val startTime = queryGroupEndpointTime(earliest, botId, groupId) ?: return@forEach
                    val endTime = queryGroupEndpointTime(latest, botId, groupId)?.safeNextSecond()
                        ?: return@forEach
                    val candidate = GroupTimeBounds(
                        botId = botId,
                        groupId = groupId,
                        startTime = startTime,
                        endTime = endTime,
                    )
                    val current = selectedByGroup[groupId]
                    if (current == null ||
                        candidate.endTime > current.endTime ||
                        (candidate.endTime == current.endTime && candidate.botId < current.botId)
                    ) {
                        selectedByGroup[groupId] = candidate
                    }
                }
            }
        }
        selectedByGroup.values.sortedWith(
            compareByDescending<GroupTimeBounds> { it.endTime }
                .thenBy { it.groupId }
                .thenBy { it.botId }
        )
    }

    private fun queryGroupEndpointTime(
        statement: PreparedStatement,
        botId: Long,
        groupId: Long,
    ): Int? {
        statement.setLong(1, botId)
        statement.setInt(2, MessageSourceKind.GROUP.ordinal)
        statement.setLong(3, groupId)
        return statement.executeQuery().use { results ->
            if (results.next()) results.getInt("time") else null
        }
    }

    fun filterGroupRangesByMinimumMessageCount(
        ranges: List<GroupTimeBounds>,
        minimumMessages: Int,
    ): List<GroupTimeBounds> {
        if (ranges.isEmpty()) return emptyList()
        val requiredMessages = minimumMessages.coerceAtLeast(1)
        return openReadConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT 1
                FROM message_record
                WHERE bot_id = ? AND target_id = ? AND kind = ? AND recalled = 0
                  AND time >= ? AND time < ?
                ORDER BY time ASC, id ASC
                LIMIT ?
                """.trimIndent()
            ).use { statement ->
                ranges.filter { range ->
                    statement.setLong(1, range.botId)
                    statement.setLong(2, range.groupId)
                    statement.setInt(3, MessageSourceKind.GROUP.ordinal)
                    statement.setInt(4, range.startTime)
                    statement.setInt(5, range.endTime)
                    statement.setInt(6, requiredMessages)
                    statement.executeQuery().use { results ->
                        var messages = 0
                        while (messages < requiredMessages && results.next()) messages++
                        messages >= requiredMessages
                    }
                }
            }
        }
    }

    fun filterGroupRangesByOldestPendingMessageTime(
        ranges: List<GroupTimeBounds>,
        oldestAllowedTime: Int,
    ): List<GroupTimeBounds> {
        if (ranges.isEmpty()) return emptyList()
        require(oldestAllowedTime >= 0) { "oldestAllowedTime must not be negative" }
        return openReadConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT MIN(time) AS oldest_time
                FROM message_record
                WHERE bot_id = ? AND target_id = ? AND kind = ? AND recalled = 0
                  AND time >= ? AND time < ?
                """.trimIndent()
            ).use { statement ->
                ranges.filter { range ->
                    statement.setLong(1, range.botId)
                    statement.setLong(2, range.groupId)
                    statement.setInt(3, MessageSourceKind.GROUP.ordinal)
                    statement.setInt(4, range.startTime)
                    statement.setInt(5, range.endTime)
                    statement.executeQuery().use { results ->
                        check(results.next()) { "读取群 ${range.groupId} 最早待处理消息失败" }
                        val oldestTime = results.getInt("oldest_time")
                        results.wasNull() || oldestTime >= oldestAllowedTime
                    }
                }
            }
        }
    }

    fun loadNextBatch(
        userId: Long,
        startTime: Int,
        snapshotEndTime: Int,
        targetMessageLimit: Int,
        maxEpisodes: Int,
        episodeGapSeconds: Int,
        contextBeforeMessages: Int,
        contextAfterMessages: Int,
        contextCoreMessages: Int,
        maxMessageChars: Int,
    ): ProfileHistoryBatch? {
        require(startTime <= snapshotEndTime) { "startTime must not be after snapshotEndTime" }
        return openReadConnection().use { connection ->
            val firstPage = queryTargetMessages(
                connection,
                userId,
                startTime,
                snapshotEndTime,
                targetMessageLimit.coerceAtLeast(1),
            )
            if (firstPage.isEmpty()) return@use null

            val pageEndTime = firstPage.last().time.safeNextSecond()
            val pageTargetMessages = queryTargetMessages(
                connection,
                userId,
                startTime,
                pageEndTime,
                Int.MAX_VALUE,
            )
            val pageEpisodes = buildEpisodes(pageTargetMessages, episodeGapSeconds.coerceAtLeast(0))
            val initialEpisodes = pageEpisodes.take(maxEpisodes.coerceAtLeast(1))
            val lastSelectedTime = initialEpisodes.last().targetMessages.maxOf { it.time }
            val episodes = pageEpisodes.takeWhile { episode ->
                episode.targetMessages.first().time <= lastSelectedTime
            }
            val targetMessages = episodes.flatMap(Episode::targetMessages)
            val endTime = targetMessages.maxOf { it.time }.safeNextSecond()
            val recordsByFingerprint = linkedMapOf<String, Pair<Int, ChatMessageRecord>>()
            val perEpisodeCoreLimit = (contextCoreMessages.coerceAtLeast(1) / episodes.size)
                .coerceAtLeast(1)

            episodes.forEach { episode ->
                val firstTargetTime = episode.targetMessages.minOf { it.time }
                val lastTargetTime = episode.targetMessages.maxOf { it.time }
                val records = buildList {
                    addAll(queryContextBefore(connection, episode.groupId, firstTargetTime, contextBeforeMessages))
                    addAll(
                        queryContextCore(
                            connection,
                            episode.groupId,
                            firstTargetTime,
                            lastTargetTime.safeNextSecond(),
                            perEpisodeCoreLimit,
                        )
                    )
                    addAll(queryContextAfter(connection, episode.groupId, lastTargetTime, contextAfterMessages))
                    addAll(episode.targetMessages)
                }
                records.forEach { record ->
                    recordsByFingerprint.putIfAbsent(record.fingerprint(), episode.index to record)
                }
            }

            val targetFingerprints = targetMessages.mapTo(hashSetOf()) { it.fingerprint() }
            val targetRecords = recordsByFingerprint.values.filter { it.second.fingerprint() in targetFingerprints }
            val contextRecords = recordsByFingerprint.values
                .asSequence()
                .filter { it.second.fingerprint() !in targetFingerprints }
                .sortedWith(
                    compareBy<Pair<Int, ChatMessageRecord>>(
                        { candidate -> contextDistance(candidate.second, targetMessages) },
                        { it.second.time },
                        { it.second.targetId },
                        { it.second.fromId },
                    )
                )
                .take(contextCoreMessages.coerceAtLeast(0))
                .toList()
            val records = (targetRecords + contextRecords).sortedWith(
                compareBy<Pair<Int, ChatMessageRecord>>(
                    { it.second.time },
                    { it.first },
                    { it.second.targetId },
                    { it.second.fromId },
                    { it.second.code },
                )
            )
            createBatch(userId, startTime, endTime, records, maxMessageChars)
        }
    }

    fun loadConversationBatch(
        botId: Long,
        groupId: Long,
        startTime: Int,
        endTime: Int,
        messageLimit: Int,
        maxMessageChars: Int,
    ): ConversationProfileBatch? {
        require(startTime < endTime) { "startTime must be before endTime" }
        return openReadConnection().use { connection ->
            val records = queryConversationMessages(
                connection = connection,
                botId = botId,
                groupId = groupId,
                startTime = startTime,
                endTime = endTime,
                limit = messageLimit.coerceAtLeast(1),
            )
            if (records.isEmpty()) return@use null
            createConversationBatch(botId, groupId, startTime, endTime, records, maxMessageChars)
        }
    }

    fun loadLatestConversationBatch(
        botId: Long,
        groupId: Long,
        startTime: Int,
        endTime: Int,
        maxMessageChars: Int,
        idleGapSeconds: Int = ProfileConversationWindowDefaults.IDLE_GAP_SECONDS,
        maxMessages: Int = ProfileConversationWindowDefaults.MAX_MESSAGES,
        maxContentChars: Int = ProfileConversationWindowDefaults.MAX_CONTENT_CHARS,
        maxSpanSeconds: Int = ProfileConversationWindowDefaults.MAX_PACKED_SPAN_SECONDS,
    ): ConversationProfileBatch? {
        require(startTime < endTime) { "startTime must be before endTime" }
        val hardMessageLimit = maxMessages.coerceAtLeast(1)
        return openReadConnection().use { connection ->
            val recentRecords = queryConversationMessages(
                connection = connection,
                botId = botId,
                groupId = groupId,
                startTime = startTime,
                endTime = endTime,
                limit = hardMessageLimit.safeIncrement(),
            )
            val records = selectLatestContinuousConversation(
                records = recentRecords,
                idleGapSeconds = idleGapSeconds.coerceAtLeast(0),
                maxMessages = hardMessageLimit,
                maxContentChars = maxContentChars.coerceAtLeast(1),
                maxSpanSeconds = maxSpanSeconds.coerceAtLeast(1),
                maxMessageChars = maxMessageChars,
            )
            if (records.isEmpty()) return@use null
            createConversationBatch(
                botId = botId,
                groupId = groupId,
                startTime = records.first().time,
                endTime = endTime,
                records = records,
                maxMessageChars = maxMessageChars,
                episodeGapSeconds = idleGapSeconds.coerceAtLeast(0),
            )
        }
    }

    fun loadNextConversationBatch(
        botId: Long,
        groupId: Long,
        startTime: Int,
        snapshotEndTime: Int,
        messageLimit: Int,
        maxMessageChars: Int,
        idleGapSeconds: Int = ProfileConversationWindowDefaults.IDLE_GAP_SECONDS,
        targetContentChars: Int = ProfileConversationWindowDefaults.TARGET_CONTENT_CHARS,
        maxMessages: Int = ProfileConversationWindowDefaults.MAX_MESSAGES,
        maxContentChars: Int = ProfileConversationWindowDefaults.MAX_CONTENT_CHARS,
        maxPackedSpanSeconds: Int = ProfileConversationWindowDefaults.MAX_PACKED_SPAN_SECONDS,
    ): ConversationProfileBatch? {
        require(startTime <= snapshotEndTime) { "startTime must not be after snapshotEndTime" }
        if (startTime == snapshotEndTime) return null
        val targetMessages = messageLimit.coerceAtLeast(1)
        val adaptiveGap = idleGapSeconds.coerceAtLeast(0)
        val hardMessageLimit = maxMessages.coerceAtLeast(targetMessages)
        val adaptiveWindow = adaptiveGap > 0
        return openReadConnection().use { connection ->
            val firstPage = queryOldestConversationMessages(
                connection = connection,
                botId = botId,
                groupId = groupId,
                startTime = startTime,
                endTime = snapshotEndTime,
                limit = if (adaptiveWindow) hardMessageLimit.safeIncrement() else targetMessages,
            )
            if (firstPage.isEmpty()) return@use null

            val endTime = if (adaptiveWindow) {
                selectConversationWindowEndTime(
                    records = firstPage,
                    idleGapSeconds = adaptiveGap,
                    targetMessages = targetMessages,
                    targetContentChars = targetContentChars.coerceAtLeast(1),
                    maxMessages = hardMessageLimit,
                    maxContentChars = maxContentChars.coerceAtLeast(targetContentChars.coerceAtLeast(1)),
                    maxPackedSpanSeconds = maxPackedSpanSeconds.coerceAtLeast(1),
                    maxMessageChars = maxMessageChars,
                )
            } else {
                firstPage.last().time.safeNextSecond()
            }
            val records = queryOldestConversationMessages(
                connection = connection,
                botId = botId,
                groupId = groupId,
                startTime = startTime,
                endTime = endTime,
                limit = Int.MAX_VALUE,
            )
            createConversationBatch(
                botId = botId,
                groupId = groupId,
                startTime = startTime,
                endTime = endTime,
                records = records,
                maxMessageChars = maxMessageChars,
                episodeGapSeconds = adaptiveGap.takeIf { adaptiveWindow },
            )
        }
    }

    private fun selectConversationWindowEndTime(
        records: List<ChatMessageRecord>,
        idleGapSeconds: Int,
        targetMessages: Int,
        targetContentChars: Int,
        maxMessages: Int,
        maxContentChars: Int,
        maxPackedSpanSeconds: Int,
        maxMessageChars: Int,
    ): Int {
        var selectedMessages = 0
        var selectedChars = 0L
        val targetContentLimit = targetContentChars.toLong()
        val hardContentLimit = maxContentChars.toLong().coerceAtLeast(targetContentLimit)
        val firstTime = records.first().time
        var previousTime = firstTime

        records.forEachIndexed { index, record ->
            val timeBoundary = index > 0 && record.time != previousTime
            if (timeBoundary && selectedMessages > 0) {
                val startsNewEpisode = record.time - previousTime > idleGapSeconds
                val nextChars = estimatePromptChars(record, maxMessageChars)
                val exceedsHardSize = selectedMessages >= maxMessages || selectedChars + nextChars > hardContentLimit
                val exceedsPackedSpan = startsNewEpisode && record.time - firstTime > maxPackedSpanSeconds
                val reachedTargetAtEpisodeBoundary = startsNewEpisode &&
                        (selectedMessages >= targetMessages || selectedChars >= targetContentLimit)
                if (exceedsHardSize || exceedsPackedSpan || reachedTargetAtEpisodeBoundary) {
                    return records[index - 1].time.safeNextSecond()
                }
            }
            selectedMessages++
            selectedChars += estimatePromptChars(record, maxMessageChars)
            previousTime = record.time
        }
        return records.last().time.safeNextSecond()
    }

    private fun selectLatestContinuousConversation(
        records: List<ChatMessageRecord>,
        idleGapSeconds: Int,
        maxMessages: Int,
        maxContentChars: Int,
        maxSpanSeconds: Int,
        maxMessageChars: Int,
    ): List<ChatMessageRecord> {
        if (records.isEmpty()) return emptyList()

        val latestTime = records.last().time
        var newerTime = latestTime
        var startIndex = records.lastIndex
        var selectedMessages = 0
        var selectedChars = 0L

        for (index in records.lastIndex downTo 0) {
            val record = records[index]
            if (selectedMessages > 0) {
                val startsEarlierConversation = newerTime.toLong() - record.time > idleGapSeconds
                val exceedsHardSize = selectedMessages >= maxMessages ||
                        selectedChars + estimatePromptChars(record, maxMessageChars) > maxContentChars.toLong()
                val exceedsSpan = latestTime.toLong() - record.time > maxSpanSeconds
                if (startsEarlierConversation || exceedsHardSize || exceedsSpan) break
            }

            startIndex = index
            selectedMessages++
            selectedChars += estimatePromptChars(record, maxMessageChars)
            newerTime = record.time
        }
        return records.subList(startIndex, records.size)
    }

    private fun estimatePromptChars(record: ChatMessageRecord, maxMessageChars: Int): Int =
        record.code.length.coerceAtMost(maxMessageChars.coerceAtLeast(80))

    private fun queryTargetMessages(
        connection: Connection,
        userId: Long,
        startTime: Int,
        endTime: Int,
        limit: Int,
    ): List<ChatMessageRecord> {
        val sql = buildString {
            append(
                """
                SELECT id, bot_id, from_id, target_id, ids, internal_ids,
                       time, kind, code, recalled
                FROM message_record
                WHERE kind = ? AND recalled = 0 AND from_id = ?
                  AND time >= ? AND time < ?
                ORDER BY time ASC
                """.trimIndent()
            )
            if (limit != Int.MAX_VALUE) append(" LIMIT ?")
        }
        return connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, MessageSourceKind.GROUP.ordinal)
            statement.setLong(2, userId)
            statement.setInt(3, startTime)
            statement.setInt(4, endTime)
            if (limit != Int.MAX_VALUE) statement.setInt(5, limit)
            statement.executeQuery().use(::readRecords)
        }
    }

    private fun queryConversationMessages(
        connection: Connection,
        botId: Long,
        groupId: Long,
        startTime: Int,
        endTime: Int,
        limit: Int,
    ): List<ChatMessageRecord> {
        return connection.prepareStatement(
            """
            SELECT id, bot_id, from_id, target_id, ids, internal_ids,
                   time, kind, code, recalled
            FROM message_record
            WHERE bot_id = ? AND target_id = ? AND kind = ? AND recalled = 0
              AND time >= ? AND time < ?
            ORDER BY time DESC, id DESC
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, botId)
            statement.setLong(2, groupId)
            statement.setInt(3, MessageSourceKind.GROUP.ordinal)
            statement.setInt(4, startTime)
            statement.setInt(5, endTime)
            statement.setInt(6, limit)
            statement.executeQuery().use(::readRecords).asReversed()
        }
    }

    private fun queryOldestConversationMessages(
        connection: Connection,
        botId: Long,
        groupId: Long,
        startTime: Int,
        endTime: Int,
        limit: Int,
    ): List<ChatMessageRecord> {
        return connection.prepareStatement(
            """
            SELECT id, bot_id, from_id, target_id, ids, internal_ids,
                   time, kind, code, recalled
            FROM message_record
            WHERE bot_id = ? AND target_id = ? AND kind = ? AND recalled = 0
              AND time >= ? AND time < ?
            ORDER BY time ASC, id ASC
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, botId)
            statement.setLong(2, groupId)
            statement.setInt(3, MessageSourceKind.GROUP.ordinal)
            statement.setInt(4, startTime)
            statement.setInt(5, endTime)
            statement.setInt(6, limit)
            statement.executeQuery().use(::readRecords)
        }
    }

    private fun createConversationBatch(
        botId: Long,
        groupId: Long,
        startTime: Int,
        endTime: Int,
        records: List<ChatMessageRecord>,
        maxMessageChars: Int,
        episodeGapSeconds: Int? = null,
    ): ConversationProfileBatch {
        val participantIds = buildSet {
            add(botId)
            records.forEach { record ->
                add(record.fromId)
                addAll(ProfileMessageRenderer.referencedUserIds(record))
            }
        }
        val aliases = buildMap {
            put(botId, "BOT")
            participantIds.asSequence()
                .filter { it != botId }
                .sorted()
                .forEachIndexed { index, participantId -> put(participantId, "U${index + 1}") }
        }
        var episodeIndex = 1
        var previousTime: Int? = null
        val promptMessages = records.mapIndexed { index, record ->
            val lastTime = previousTime
            if (episodeGapSeconds != null && lastTime != null && record.time - lastTime > episodeGapSeconds) {
                episodeIndex++
            }
            previousTime = record.time
            ProfilePromptMessage(
                record = record,
                text = ProfileMessageRenderer.render(record, aliases, maxMessageChars.coerceAtLeast(80)),
                evidenceRef = index + 1,
                episodeIndex = episodeIndex,
            )
        }
        return ConversationProfileBatch(
            botId = botId,
            groupId = groupId,
            startTime = startTime,
            endTime = endTime,
            messages = promptMessages,
            aliases = aliases,
            inputHash = calculateInputHash(promptMessages),
            contactHints = loadContactHints(records, participantIds),
        )
    }

    private fun createBatch(
        userId: Long,
        startTime: Int,
        endTime: Int,
        records: List<Pair<Int, ChatMessageRecord>>,
        maxMessageChars: Int,
    ): ProfileHistoryBatch {
        val participantIds = buildSet {
            add(userId)
            records.forEach { (_, record) ->
                add(record.fromId)
                addAll(ProfileMessageRenderer.referencedUserIds(record))
            }
        }
        val aliases = buildMap {
            put(userId, "TARGET")
            participantIds.asSequence()
                .filter { it != userId }
                .sorted()
                .forEachIndexed { index, participantId -> put(participantId, "U${index + 1}") }
        }

        var evidenceRef = 0
        val promptMessages = records.map { (episodeIndex, record) ->
            val ref = if (record.time >= startTime && record.time < endTime) ++evidenceRef else null
            ProfilePromptMessage(
                record = record,
                text = ProfileMessageRenderer.render(record, aliases, maxMessageChars.coerceAtLeast(80)),
                evidenceRef = ref,
                episodeIndex = episodeIndex,
            )
        }
        return ProfileHistoryBatch(
            userId = userId,
            startTime = startTime,
            endTime = endTime,
            messages = promptMessages,
            aliases = aliases,
            inputHash = calculateInputHash(promptMessages),
            contactHints = loadContactHints(records.map { it.second }, participantIds),
        )
    }

    private fun loadContactHints(
        records: List<ChatMessageRecord>,
        participantIds: Set<Long>,
    ): Map<Long, ContactProfileHint> {
        if (records.isEmpty() || participantIds.isEmpty()) return emptyMap()
        return records.groupBy(ChatMessageRecord::botId)
            .values
            .fold(emptyMap()) { accumulated, botRecords ->
                val botId = botRecords.first().botId
                val groupIds = botRecords.mapTo(hashSetOf(), ChatMessageRecord::targetId)
                val hints = runCatching {
                    ContactSnapshotStore.loadProfileHints(
                        databaseFile = databaseFile,
                        botId = botId,
                        groupIds = groupIds,
                        userIds = participantIds,
                    )
                }.getOrDefault(emptyMap())
                mergeContactHints(accumulated, hints)
            }
    }

    private fun mergeContactHints(
        left: Map<Long, ContactProfileHint>,
        right: Map<Long, ContactProfileHint>,
    ): Map<Long, ContactProfileHint> {
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        return buildMap {
            putAll(left)
            right.forEach { (userId, hint) ->
                val current = this[userId]
                put(
                    userId,
                    if (current == null) {
                        hint
                    } else {
                        hint.copy(
                            nickname = hint.nickname.ifBlank { current.nickname },
                            remark = hint.remark.ifBlank { current.remark },
                            sex = hint.sex.ifBlank { current.sex },
                            age = hint.age.takeIf { it > 0 } ?: current.age,
                            qLevel = hint.qLevel.takeIf { it > 0 } ?: current.qLevel,
                            sign = hint.sign.ifBlank { current.sign },
                            isFriend = hint.isFriend || current.isFriend,
                            memberships = (current.memberships + hint.memberships)
                                .distinctBy { it.groupId },
                        )
                    },
                )
            }
        }
    }

    private fun contextDistance(
        record: ChatMessageRecord,
        targetMessages: List<ChatMessageRecord>,
    ): Long = targetMessages.asSequence()
        .filter { it.targetId == record.targetId }
        .minOfOrNull { target -> kotlin.math.abs(target.time.toLong() - record.time.toLong()) }
        ?: Long.MAX_VALUE

    private fun buildEpisodes(
        targetMessages: List<ChatMessageRecord>,
        gapSeconds: Int,
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        var current = mutableListOf<ChatMessageRecord>()
        var currentGroup = 0L
        var lastTime = 0

        fun flush() {
            if (current.isNotEmpty()) {
                episodes += Episode(episodes.size + 1, currentGroup, current.toList())
                current = mutableListOf()
            }
        }

        targetMessages.forEach { message ->
            if (current.isNotEmpty() &&
                (message.targetId != currentGroup || message.time - lastTime > gapSeconds)
            ) {
                flush()
            }
            if (current.isEmpty()) currentGroup = message.targetId
            current += message
            lastTime = message.time
        }
        flush()
        return episodes
    }

    private fun queryContextBefore(
        connection: Connection,
        groupId: Long,
        beforeTime: Int,
        limit: Int,
    ): List<ChatMessageRecord> {
        if (limit <= 0) return emptyList()
        return queryGroupContext(
            connection,
            "target_id = ? AND kind = ? AND recalled = 0 AND time < ? ORDER BY time DESC LIMIT ?",
            groupId,
            beforeTime,
            limit,
        ).asReversed()
    }

    private fun queryContextAfter(
        connection: Connection,
        groupId: Long,
        afterTime: Int,
        limit: Int,
    ): List<ChatMessageRecord> {
        if (limit <= 0) return emptyList()
        return queryGroupContext(
            connection,
            "target_id = ? AND kind = ? AND recalled = 0 AND time > ? ORDER BY time ASC LIMIT ?",
            groupId,
            afterTime,
            limit,
        )
    }

    private fun queryContextCore(
        connection: Connection,
        groupId: Long,
        startTime: Int,
        endTime: Int,
        limit: Int,
    ): List<ChatMessageRecord> {
        if (limit <= 0) return emptyList()
        return connection.prepareStatement(
            """
            SELECT id, bot_id, from_id, target_id, ids, internal_ids,
                   time, kind, code, recalled
            FROM message_record
            WHERE target_id = ? AND kind = ? AND recalled = 0
              AND time >= ? AND time < ?
            ORDER BY time ASC
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, groupId)
            statement.setInt(2, MessageSourceKind.GROUP.ordinal)
            statement.setInt(3, startTime)
            statement.setInt(4, endTime)
            statement.setInt(5, limit)
            statement.executeQuery().use(::readRecords)
        }
    }

    private fun queryGroupContext(
        connection: Connection,
        predicate: String,
        groupId: Long,
        boundaryTime: Int,
        limit: Int,
    ): List<ChatMessageRecord> = connection.prepareStatement(
        """
        SELECT id, bot_id, from_id, target_id, ids, internal_ids,
               time, kind, code, recalled
        FROM message_record
        WHERE $predicate
        """.trimIndent()
    ).use { statement ->
        statement.setLong(1, groupId)
        statement.setInt(2, MessageSourceKind.GROUP.ordinal)
        statement.setInt(3, boundaryTime)
        statement.setInt(4, limit)
        statement.executeQuery().use(::readRecords)
    }

    private fun readRecords(results: ResultSet): List<ChatMessageRecord> = buildList {
        while (results.next()) add(results.toRecord())
    }

    private fun ResultSet.toRecord(): ChatMessageRecord {
        val kind = MessageSourceKind.values().getOrNull(getInt("kind"))
            ?: throw IllegalStateException("未知的消息类型")
        return ChatMessageRecord(
            id = getLong("id"),
            botId = getLong("bot_id"),
            fromId = getLong("from_id"),
            targetId = getLong("target_id"),
            ids = getString("ids"),
            internalIds = getString("internal_ids"),
            time = getInt("time"),
            kind = kind,
            code = getString("code"),
            recalled = getInt("recalled"),
        )
    }

    private fun openReadConnection(): Connection {
        val config = SQLiteConfig().apply {
            setReadOnly(true)
            setBusyTimeout(30_000)
        }
        return DriverManager.getConnection(
            "jdbc:sqlite:${databaseFile.absolutePath}",
            config.toProperties(),
        ).also { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA query_only=ON")
            }
        }
    }

    private fun ChatMessageRecord.fingerprint(): String =
        "$botId|$fromId|$targetId|$time|${kind.ordinal}|$code".sha256()

    private fun calculateInputHash(messages: List<ProfilePromptMessage>): String = buildString {
        messages.forEach { message ->
            append(message.episodeIndex).append('|')
            append(message.evidenceRef ?: 0).append('|')
            append(message.record.fingerprint()).append('\n')
        }
    }.sha256()

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun Int.safeNextSecond(): Int = if (this == Int.MAX_VALUE) this else this + 1

    private fun Int.safeIncrement(): Int = if (this == Int.MAX_VALUE) this else this + 1

    private companion object {
        const val GROUP_ENDPOINT_TIME_SQL = """
            SELECT time
            FROM message_record
            WHERE bot_id = ? AND kind = ? AND target_id = ? AND recalled = 0
            ORDER BY time %s, id %s
            LIMIT 1
        """
    }
}
