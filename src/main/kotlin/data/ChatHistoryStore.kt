package top.jie65535.mirai.data

import net.mamoe.mirai.Bot
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.contact.Friend
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.Member
import net.mamoe.mirai.contact.Stranger
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.event.events.MessagePostSendEvent
import net.mamoe.mirai.event.events.MessageRecallEvent
import net.mamoe.mirai.message.data.MessageSource
import net.mamoe.mirai.message.data.MessageSourceKind
import net.mamoe.mirai.message.data.source
import net.mamoe.mirai.message.data.toMessageChain
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * 基于 SQLite 的聊天记录存储。
 *
 * 写连接由 [writeLock] 串行保护；SQLite 使用 WAL + NORMAL synchronous，允许并发读取。
 */
object ChatHistoryStore {
    private const val SCHEMA_VERSION = 3
    private const val BUSY_TIMEOUT_MS = 30_000
    private const val SEARCH_FTS_TABLE = "message_record_fts"
    private const val SEARCH_TEXT_TABLE = "message_record_search"
    private const val SENDER_ALIAS_TABLE = "chat_history_sender_alias"
    private const val SEARCH_BACKFILL_CURSOR_KEY = "search_backfill_cursor"
    private const val SEARCH_INDEX_COMPLETE_KEY = "search_index_complete"
    private const val DEFAULT_BACKFILL_BATCH_SIZE = 250
    private const val MAX_PAGE_SIZE = 200
    private const val MAX_CONTEXT_MESSAGES = 100

    private val lifecycleLock = Any()
    private val writeLock = Any()

    @Volatile
    private var initialized = false
    @Volatile
    private var searchIndexAvailable = false
    @Volatile
    private var searchIndexReady = false
    private lateinit var databaseFile: File
    private var writeConnection: Connection? = null
    private var warningLogger: ((String, Throwable?) -> Unit)? = null

    val isAvailable: Boolean
        get() = initialized

    val isSearchIndexAvailable: Boolean
        get() = initialized && searchIndexAvailable

    val isSearchIndexReady: Boolean
        get() = isSearchIndexAvailable && searchIndexReady

    val databaseFileOrNull: File?
        get() = if (initialized) databaseFile else null

    fun init(
        dataFolder: File,
        onWarning: (String, Throwable?) -> Unit = { _, _ -> },
    ) {
        synchronized(lifecycleLock) {
            if (initialized) return

            warningLogger = onWarning
            Class.forName("org.sqlite.JDBC")
            dataFolder.mkdirs()
            databaseFile = dataFolder.resolve("chat-history.sqlite")

            val connection = openConnection()
            try {
                configureWriteConnection(connection)
                createSchema(connection)
                writeConnection = connection
                initialized = true
                searchIndexAvailable = hasSearchIndex(connection)
                searchIndexReady = searchIndexAvailable && readMeta(connection, SEARCH_INDEX_COMPLETE_KEY) == "1"
            } catch (cause: Throwable) {
                connection.close()
                throw cause
            }
        }
    }

    fun close() {
        synchronized(lifecycleLock) {
            if (!initialized) return
            synchronized(writeLock) {
                writeConnection?.let { connection ->
                    runCatching {
                        connection.createStatement().use { statement ->
                            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                        }
                    }.onFailure { warn("SQLite WAL checkpoint 失败", it) }
                    connection.close()
                }
                writeConnection = null
                initialized = false
                searchIndexAvailable = false
                searchIndexReady = false
                warningLogger = null
            }
        }
    }

    fun record(event: MessageEvent) {
        if (!initialized) return
        val message = event.message.asSequence()
            .filterNot { it is MessageSource }
            .toMessageChain()
        insertRecord(ChatMessageRecord.fromSuccess(event.message.source, message), event.senderName)
    }

    fun record(event: MessagePostSendEvent<*>) {
        if (!initialized) return
        val source = event.receipt?.source ?: return
        val message = event.message.asSequence()
            .filterNot { it is MessageSource }
            .toMessageChain()
        insertRecord(ChatMessageRecord.fromSuccess(source, message), event.bot.nick)
    }

    fun markRecalled(event: MessageRecallEvent) {
        if (!initialized) return
        val (kind, targetId, recalled) = when (event) {
            is MessageRecallEvent.FriendRecall -> Triple(
                MessageSourceKind.FRIEND,
                event.bot.id,
                2,
            )
            is MessageRecallEvent.GroupRecall -> Triple(
                MessageSourceKind.GROUP,
                event.group.id,
                if ((event.operator?.id ?: event.bot.id) == event.authorId) 2 else 3,
            )
        }
        val messageIds = event.messageIds.joinToString(",")
        val messageInternalIds = event.messageInternalIds.joinToString(",")

        withWriteConnection { connection ->
            connection.prepareStatement(
                """
                UPDATE message_record
                SET recalled = ?
                WHERE id = (
                    SELECT id
                    FROM message_record
                    WHERE bot_id = ?
                      AND kind = ?
                      AND from_id = ?
                      AND target_id = ?
                      AND (ids = ? OR internal_ids = ?)
                    ORDER BY ABS(time - ?) ASC, id DESC
                    LIMIT 1
                )
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, recalled)
                statement.setLong(2, event.bot.id)
                statement.setInt(3, kind.ordinal)
                statement.setLong(4, event.authorId)
                statement.setLong(5, targetId)
                statement.setString(6, messageIds)
                statement.setString(7, messageInternalIds)
                statement.setInt(8, event.messageTime)
                val updated = statement.executeUpdate()
                if (updated == 0) {
                    warn(
                        "未在 SQLite 中找到撤回消息: bot=${event.bot.id}, " +
                                "author=${event.authorId}, target=$targetId, ids=$messageIds, " +
                                "internalIds=$messageInternalIds, " +
                                "time=${event.messageTime}"
                    )
                }
            }
        }
    }

    fun query(
        contact: Contact,
        start: Int,
        end: Int,
        limit: Int? = null,
        fromId: Long? = null,
    ): List<ChatMessageRecord> {
        check(initialized) { "聊天记录数据库尚未初始化" }
        require(start <= end) { "start must not be after end" }

        val conditions = mutableListOf<String>()
        val parameters = mutableListOf<Any>()

        conditions += "bot_id = ?"
        parameters += contact.bot.id
        conditions += "time BETWEEN ? AND ?"
        parameters += start
        parameters += end

        when (contact) {
            is Group -> {
                conditions += "kind = ?"
                parameters += MessageSourceKind.GROUP.ordinal
                conditions += "target_id = ?"
                parameters += contact.id
            }
            is Member -> {
                conditions += "kind = ?"
                parameters += MessageSourceKind.GROUP.ordinal
                conditions += "target_id = ?"
                parameters += contact.group.id
                conditions += "from_id = ?"
                parameters += contact.id
            }
            is Friend -> {
                conditions += "kind = ?"
                parameters += MessageSourceKind.FRIEND.ordinal
                conditions += "(from_id = ? OR target_id = ?)"
                parameters += contact.id
                parameters += contact.id
            }
            is Stranger -> {
                conditions += "kind = ?"
                parameters += MessageSourceKind.STRANGER.ordinal
                conditions += "(from_id = ? OR target_id = ?)"
                parameters += contact.id
                parameters += contact.id
            }
            is Bot -> Unit
            else -> error("不支持查询的联系人 $contact")
        }

        if (fromId != null && contact !is Member) {
            conditions += "from_id = ?"
            parameters += fromId
        }

        val sql = buildString {
            append(
                """
                SELECT id, bot_id, from_id, target_id, ids, internal_ids,
                       time, kind, code, recalled
                FROM message_record
                WHERE
                """.trimIndent()
            )
            append(' ')
            append(conditions.joinToString(" AND "))
            append(" ORDER BY time DESC, id DESC")
            if (limit != null) append(" LIMIT ?")
        }

        return openReadConnection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                parameters.forEachIndexed { index, value ->
                    when (value) {
                        is Int -> statement.setInt(index + 1, value)
                        is Long -> statement.setLong(index + 1, value)
                        else -> error("不支持的查询参数类型 ${value::class}")
                    }
                }
                if (limit != null) {
                    statement.setInt(parameters.size + 1, limit.coerceAtLeast(1))
                }
                statement.executeQuery().use { results ->
                    buildList {
                        while (results.next()) {
                            add(results.toRecord())
                        }
                    }
                }
            }
        }
    }

    fun search(request: ChatHistorySearchRequest): ChatHistorySearchPage {
        check(initialized) { "聊天记录数据库尚未初始化" }
        require(request.limit > 0) { "limit must be positive" }
        require(request.start == null || request.end == null || request.start <= request.end) {
            "start must not be after end"
        }

        val parameters = mutableListOf<Any>()
        val conditions = subjectConditions(request.subject, parameters).toMutableList()
        request.start?.let {
            conditions += "mr.time >= ?"
            parameters += it
        }
        request.end?.let {
            conditions += "mr.time <= ?"
            parameters += it
        }
        request.fromId?.let {
            conditions += "mr.from_id = ?"
            parameters += it
        }

        val query = request.query?.trim().orEmpty()
        val textPredicate = buildTextPredicate(query, request.matchMode, parameters)
        if ((textPredicate.usesFts || textPredicate.usesSearchText) && !isSearchIndexAvailable) {
            error("聊天记录全文索引尚未就绪")
        }
        val joins = buildString {
            if (textPredicate.usesSearchText) {
                append("JOIN $SEARCH_TEXT_TABLE search ON search.message_id = mr.id ")
            }
            if (textPredicate.usesFts) {
                append("JOIN $SEARCH_FTS_TABLE ON $SEARCH_FTS_TABLE.rowid = mr.id")
            }
        }
        textPredicate.sql?.let(conditions::add)

        request.cursor?.let { cursor ->
            val operator = if (request.sortOrder == ChatHistorySortOrder.NEWEST) "<" else ">"
            conditions += "(mr.time $operator ? OR (mr.time = ? AND mr.id $operator ?))"
            parameters += cursor.time
            parameters += cursor.time
            parameters += cursor.id
        }

        val where = conditions.joinToString(" AND ")
        val order = if (request.sortOrder == ChatHistorySortOrder.NEWEST) {
            "mr.time DESC, mr.id DESC"
        } else {
            "mr.time ASC, mr.id ASC"
        }
        val records = openReadConnection().use { connection ->
            val sql = """
                SELECT mr.id, mr.bot_id, mr.from_id, mr.target_id, mr.ids, mr.internal_ids,
                       mr.time, mr.kind, mr.code, mr.recalled
                FROM message_record mr
                $joins
                WHERE $where
                ORDER BY $order
                LIMIT ?
            """.trimIndent()
            val queryParameters = parameters + (request.limit.coerceAtMost(MAX_PAGE_SIZE) + 1)
            loadRecords(connection, sql, queryParameters)
        }
        val hasMore = records.size > request.limit.coerceAtMost(MAX_PAGE_SIZE)
        val pageRecords = records.take(request.limit.coerceAtMost(MAX_PAGE_SIZE))
        val last = pageRecords.lastOrNull()
        val nextCursor = if (hasMore && last != null) {
            ChatHistoryCursor(last.time, last.id)
        } else {
            null
        }
        val total = if (request.cursor == null) {
            openReadConnection().use { connection ->
                val sql = "SELECT COUNT(*) FROM message_record mr $joins WHERE $where"
                connection.prepareStatement(sql).use { statement ->
                    bindParameters(statement, parameters)
                    statement.executeQuery().use { results ->
                        check(results.next())
                        results.getLong(1)
                    }
                }
            }
        } else {
            null
        }
        return ChatHistorySearchPage(pageRecords, total, nextCursor)
    }

    fun findAround(
        subject: ChatHistorySubject,
        messageId: Long,
        before: Int = 8,
        after: Int = 8,
    ): ChatHistoryContext? {
        check(initialized) { "聊天记录数据库尚未初始化" }
        require(before >= 0 && after >= 0) { "before and after must not be negative" }
        require(before <= MAX_CONTEXT_MESSAGES && after <= MAX_CONTEXT_MESSAGES) {
            "context window is too large"
        }

        return openReadConnection().use { connection ->
            val targetParameters = mutableListOf<Any>()
            val targetConditions = subjectConditions(subject, targetParameters).toMutableList()
            targetConditions += "mr.id = ?"
            targetParameters += messageId
            val target = loadRecords(
                connection,
                """
                    SELECT mr.id, mr.bot_id, mr.from_id, mr.target_id, mr.ids, mr.internal_ids,
                           mr.time, mr.kind, mr.code, mr.recalled
                    FROM message_record mr
                    WHERE ${targetConditions.joinToString(" AND ")}
                """.trimIndent(),
                targetParameters,
            ).firstOrNull() ?: return@use null

            val beforeRecords = if (before == 0) {
                emptyList()
            } else {
                val parameters = mutableListOf<Any>()
                val conditions = subjectConditions(subject, parameters).toMutableList()
                conditions += "(mr.time < ? OR (mr.time = ? AND mr.id < ?))"
                parameters += target.time
                parameters += target.time
                parameters += target.id
                conditions += "mr.id <> ?"
                parameters += target.id
                loadRecords(
                    connection,
                    """
                        SELECT mr.id, mr.bot_id, mr.from_id, mr.target_id, mr.ids, mr.internal_ids,
                               mr.time, mr.kind, mr.code, mr.recalled
                        FROM message_record mr
                        WHERE ${conditions.joinToString(" AND ")}
                        ORDER BY mr.time DESC, mr.id DESC
                        LIMIT ?
                    """.trimIndent(),
                    parameters + before,
                ).asReversed()
            }
            val afterRecords = if (after == 0) {
                emptyList()
            } else {
                val parameters = mutableListOf<Any>()
                val conditions = subjectConditions(subject, parameters).toMutableList()
                conditions += "(mr.time > ? OR (mr.time = ? AND mr.id > ?))"
                parameters += target.time
                parameters += target.time
                parameters += target.id
                conditions += "mr.id <> ?"
                parameters += target.id
                loadRecords(
                    connection,
                    """
                        SELECT mr.id, mr.bot_id, mr.from_id, mr.target_id, mr.ids, mr.internal_ids,
                               mr.time, mr.kind, mr.code, mr.recalled
                        FROM message_record mr
                        WHERE ${conditions.joinToString(" AND ")}
                        ORDER BY mr.time ASC, mr.id ASC
                        LIMIT ?
                    """.trimIndent(),
                    parameters + after,
                )
            }
            ChatHistoryContext(messageId, beforeRecords + target + afterRecords)
        }
    }

    fun findSenderAliases(
        subject: ChatHistorySubject,
        query: String,
        limit: Int = 10,
    ): List<ChatHistorySenderAliasMatch> {
        check(initialized) { "聊天记录数据库尚未初始化" }
        if (query.isBlank() || limit <= 0) return emptyList()
        val normalized = query.trim()
        return openReadConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT user_id, name
                FROM $SENDER_ALIAS_TABLE
                WHERE bot_id = ? AND kind = ? AND subject_id = ?
                  AND lower(name) LIKE ? ESCAPE '\'
                ORDER BY last_seen DESC
                LIMIT ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, subject.botId)
                statement.setInt(2, subject.kind.ordinal)
                statement.setLong(3, subject.subjectId)
                statement.setString(4, "%${normalized.lowercase().escapeLike()}%")
                statement.setInt(5, limit.coerceAtMost(50))
                statement.executeQuery().use { results ->
                    buildList {
                        while (results.next()) {
                            val name = results.getString("name")
                            add(
                                ChatHistorySenderAliasMatch(
                                    userId = results.getLong("user_id"),
                                    displayName = name,
                                    matchRank = name.aliasMatchRank(normalized),
                                )
                            )
                        }
                    }.sortedWith(compareBy({ it.matchRank }, { it.userId }))
                        .distinctBy(ChatHistorySenderAliasMatch::userId)
                        .take(limit)
                }
            }
        }
    }

    fun backfillSearchIndex(
        batchSize: Int = DEFAULT_BACKFILL_BATCH_SIZE,
        shouldContinue: () -> Boolean = { true },
        onProgress: (processed: Int) -> Unit = {},
    ): Int {
        if (!isSearchIndexAvailable || batchSize <= 0) return 0
        var processed = 0
        var cursor = withWriteConnectionResult { connection ->
            readMeta(connection, SEARCH_BACKFILL_CURSOR_KEY)?.toLongOrNull() ?: 0L
        }
        while (shouldContinue()) {
            val batch = openReadConnection().use { connection ->
                connection.prepareStatement(
                    """
                    SELECT mr.id, mr.code
                    FROM message_record mr
                    WHERE mr.id > ?
                      AND NOT EXISTS (
                          SELECT 1 FROM $SEARCH_TEXT_TABLE search WHERE search.message_id = mr.id
                      )
                    ORDER BY mr.id
                    LIMIT ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, cursor)
                    statement.setInt(2, batchSize)
                    statement.executeQuery().use { results ->
                        buildList {
                            while (results.next()) {
                                add(results.getLong("id") to results.getString("code"))
                            }
                        }
                    }
                }
            }
            if (batch.isEmpty()) {
                withWriteConnection { connection ->
                    writeMeta(connection, SEARCH_INDEX_COMPLETE_KEY, "1")
                }
                searchIndexReady = true
                return processed
            }
            withWriteConnection { connection ->
                val oldAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                        "INSERT INTO $SEARCH_TEXT_TABLE(message_id, search_text) VALUES (?, ?)"
                    ).use { insertText ->
                        connection.prepareStatement(
                            "INSERT INTO $SEARCH_FTS_TABLE(rowid, search_text) VALUES (?, ?)"
                        ).use { insertFts ->
                            batch.forEach { (id, code) ->
                                val text = ChatHistorySearchText.extract(code)
                                insertText.setLong(1, id)
                                insertText.setString(2, text)
                                insertText.addBatch()
                                insertFts.setLong(1, id)
                                insertFts.setString(2, text)
                                insertFts.addBatch()
                            }
                            insertText.executeBatch()
                            insertFts.executeBatch()
                        }
                    }
                    cursor = batch.last().first
                    writeMeta(connection, SEARCH_BACKFILL_CURSOR_KEY, cursor.toString())
                    writeMeta(connection, SEARCH_INDEX_COMPLETE_KEY, "0")
                    connection.commit()
                } catch (cause: Throwable) {
                    connection.rollback()
                    throw cause
                } finally {
                    connection.autoCommit = oldAutoCommit
                }
            }
            processed += batch.size
            onProgress(processed)
        }
        return processed
    }

    internal fun insertRecord(record: ChatMessageRecord, senderName: String = "") {
        var recordId = 0L
        var indexFailure: Throwable? = null
        withWriteConnection { connection ->
            recordId = insertPrimaryRecord(connection, record)
            if (senderName.isNotBlank()) {
                runCatching { upsertSenderAlias(connection, record, senderName) }
                    .onFailure { warn("保存聊天记录发送者别名失败: user=${record.fromId}", it) }
            }
            if (isSearchIndexAvailable) {
                runCatching { indexRecordOnConnection(connection, recordId, record.code) }
                    .onFailure { indexFailure = it }
            }
        }
        indexFailure?.let { cause ->
            searchIndexReady = false
            markSearchIndexIncomplete()
            warn("消息已保存，但写入聊天记录全文索引失败: id=$recordId", cause)
        }
    }

    private fun insertPrimaryRecord(connection: Connection, record: ChatMessageRecord): Long {
        connection.prepareStatement(
            """
            INSERT INTO message_record(
                bot_id, from_id, target_id, ids, internal_ids,
                time, kind, code, recalled
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, record.botId)
            statement.setLong(2, record.fromId)
            statement.setLong(3, record.targetId)
            statement.setString(4, record.ids)
            statement.setString(5, record.internalIds)
            statement.setInt(6, record.time)
            statement.setInt(7, record.kind.ordinal)
            statement.setString(8, record.code)
            statement.setInt(9, record.recalled)
            statement.executeUpdate()
        }
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT last_insert_rowid()").use { results ->
                check(results.next())
                return results.getLong(1)
            }
        }
    }

    private fun indexRecordOnConnection(connection: Connection, recordId: Long, code: String) {
        val searchText = ChatHistorySearchText.extract(code)
        val oldAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                "INSERT INTO $SEARCH_TEXT_TABLE(message_id, search_text) VALUES (?, ?)"
            ).use { statement ->
                statement.setLong(1, recordId)
                statement.setString(2, searchText)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO $SEARCH_FTS_TABLE(rowid, search_text) VALUES (?, ?)"
            ).use { statement ->
                statement.setLong(1, recordId)
                statement.setString(2, searchText)
                statement.executeUpdate()
            }
            connection.commit()
        } catch (cause: Throwable) {
            connection.rollback()
            throw cause
        } finally {
            connection.autoCommit = oldAutoCommit
        }
    }

    private fun upsertSenderAlias(connection: Connection, record: ChatMessageRecord, senderName: String) {
        val subjectId = if (record.kind == MessageSourceKind.GROUP) {
            record.targetId
        } else if (record.fromId == record.botId) {
            record.targetId
        } else {
            record.fromId
        }
        connection.prepareStatement(
            """
            INSERT INTO $SENDER_ALIAS_TABLE(
                bot_id, kind, subject_id, user_id, name, last_seen
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(bot_id, kind, subject_id, user_id, name) DO UPDATE SET
                last_seen = MAX(last_seen, excluded.last_seen)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, record.botId)
            statement.setInt(2, record.kind.ordinal)
            statement.setLong(3, subjectId)
            statement.setLong(4, record.fromId)
            statement.setString(5, senderName.trim())
            statement.setInt(6, record.time)
            statement.executeUpdate()
        }
    }

    private fun withWriteConnection(block: (Connection) -> Unit) {
        withWriteConnectionResult { connection -> block(connection) }
    }

    private fun <T> withWriteConnectionResult(block: (Connection) -> T): T {
        synchronized(writeLock) {
            check(initialized) { "聊天记录数据库尚未初始化" }
            val connection = writeConnection?.takeUnless(Connection::isClosed)
                ?: openConnection().also {
                    configureWriteConnection(it)
                    writeConnection = it
                }
            return block(connection)
        }
    }

    private fun openConnection(): Connection {
        return DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}")
    }

    private fun openReadConnection(): Connection {
        return openConnection().also { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout=$BUSY_TIMEOUT_MS")
                statement.execute("PRAGMA query_only=ON")
            }
        }
    }

    private fun configureWriteConnection(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA synchronous=NORMAL")
            statement.execute("PRAGMA busy_timeout=$BUSY_TIMEOUT_MS")
            statement.execute("PRAGMA wal_autocheckpoint=1000")
        }
    }

    private fun createSchema(connection: Connection) {
        val oldAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS message_record(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        bot_id INTEGER NOT NULL,
                        from_id INTEGER NOT NULL,
                        target_id INTEGER NOT NULL,
                        ids TEXT,
                        internal_ids TEXT,
                        time INTEGER NOT NULL,
                        kind INTEGER NOT NULL,
                        code TEXT NOT NULL,
                        recalled INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_message_subject_time " +
                            "ON message_record(bot_id, kind, target_id, time DESC, id DESC)"
                )
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_message_sender_subject_time " +
                            "ON message_record(bot_id, kind, target_id, from_id, time DESC, id DESC)"
                )
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_message_recall_identity " +
                            "ON message_record(bot_id, kind, target_id, from_id, time, ids)"
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS chat_history_meta(
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS $SEARCH_TEXT_TABLE(
                        message_id INTEGER PRIMARY KEY,
                        search_text TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS $SENDER_ALIAS_TABLE(
                        bot_id INTEGER NOT NULL,
                        kind INTEGER NOT NULL,
                        subject_id INTEGER NOT NULL,
                        user_id INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        last_seen INTEGER NOT NULL,
                        PRIMARY KEY(bot_id, kind, subject_id, user_id, name)
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_chat_history_sender_alias_scope " +
                            "ON $SENDER_ALIAS_TABLE(bot_id, kind, subject_id, last_seen DESC)"
                )
            }
            connection.prepareStatement(
                "INSERT INTO chat_history_meta(key, value) VALUES ('schema_version', ?) " +
                        "ON CONFLICT(key) DO UPDATE SET value = excluded.value"
            ).use { statement ->
                statement.setString(1, SCHEMA_VERSION.toString())
                statement.executeUpdate()
            }
            connection.commit()
        } catch (cause: Throwable) {
            connection.rollback()
            throw cause
        } finally {
            connection.autoCommit = oldAutoCommit
        }

        runCatching {
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS $SEARCH_FTS_TABLE USING fts5(
                        search_text,
                        content='$SEARCH_TEXT_TABLE',
                        content_rowid='message_id',
                        tokenize='trigram'
                    )
                    """.trimIndent()
                )
            }
        }.onFailure { warn("初始化 SQLite FTS5 聊天记录索引失败，消息记录仍会继续保存", it) }

        if (hasSearchIndex(connection)) {
            val hasMessages = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT 1 FROM message_record LIMIT 1").use(ResultSet::next)
            }
            connection.prepareStatement(
                "INSERT INTO chat_history_meta(key, value) VALUES (?, ?) ON CONFLICT(key) DO NOTHING"
            ).use { statement ->
                statement.setString(1, SEARCH_BACKFILL_CURSOR_KEY)
                statement.setString(2, "0")
                statement.executeUpdate()
                statement.setString(1, SEARCH_INDEX_COMPLETE_KEY)
                statement.setString(2, if (hasMessages) "0" else "1")
                statement.executeUpdate()
            }
        }
    }

    private fun subjectConditions(
        subject: ChatHistorySubject,
        parameters: MutableList<Any>,
    ): List<String> {
        val conditions = mutableListOf(
            "mr.bot_id = ?",
            "mr.kind = ?",
        )
        parameters += subject.botId
        parameters += subject.kind.ordinal
        if (subject.kind == MessageSourceKind.GROUP) {
            conditions += "mr.target_id = ?"
            parameters += subject.subjectId
        } else {
            conditions += "(mr.from_id = ? OR mr.target_id = ?)"
            parameters += subject.subjectId
            parameters += subject.subjectId
        }
        return conditions
    }

    private data class TextPredicate(
        val sql: String?,
        val usesFts: Boolean,
        val usesSearchText: Boolean,
    )

    private fun buildTextPredicate(
        query: String,
        mode: ChatHistoryMatchMode,
        parameters: MutableList<Any>,
    ): TextPredicate {
        if (query.isBlank()) return TextPredicate(null, false, false)
        val normalized = query.lowercase()
        val terms = normalized.split(Regex("\\s+")).filter(String::isNotBlank)
        val hasShortTerm = terms.any { it.codePointCount(0, it.length) < 3 }
        if (mode == ChatHistoryMatchMode.ANY && hasShortTerm) {
            val predicates = terms.map {
                parameters += it
                "instr(lower(search.search_text), ?) > 0"
            }
            return TextPredicate(predicates.joinToString(" OR ", prefix = "(", postfix = ")"), false, true)
        }
        val ftsTerms = if (mode == ChatHistoryMatchMode.PHRASE) {
            listOf(normalized)
        } else {
            terms
        }
        val ftsOnly = ftsTerms.filter { it.codePointCount(0, it.length) >= 3 }
        if (ftsOnly.isEmpty()) {
            val predicates = terms.map {
                parameters += it
                "instr(lower(search.search_text), ?) > 0"
            }
            val joiner = if (mode == ChatHistoryMatchMode.ANY) " OR " else " AND "
            return TextPredicate(predicates.joinToString(joiner, prefix = "(", postfix = ")"), false, true)
        }
        val ftsQuery = when (mode) {
            ChatHistoryMatchMode.PHRASE -> quoteFtsTerm(normalized)
            ChatHistoryMatchMode.ALL -> ftsOnly.joinToString(" AND ", transform = ::quoteFtsTerm)
            ChatHistoryMatchMode.ANY -> ftsOnly.joinToString(" OR ", transform = ::quoteFtsTerm)
        }
        parameters += ftsQuery
        val ftsPredicate = "$SEARCH_FTS_TABLE MATCH ?"
        if (mode != ChatHistoryMatchMode.ALL || !hasShortTerm) {
            return TextPredicate(ftsPredicate, true, false)
        }
        val shortPredicates = terms.filter { it.codePointCount(0, it.length) < 3 }.map {
            parameters += it
            "instr(lower(search.search_text), ?) > 0"
        }
        return TextPredicate(
            (listOf(ftsPredicate) + shortPredicates).joinToString(" AND ", prefix = "(", postfix = ")"),
            true,
            true,
        )
    }

    private fun quoteFtsTerm(term: String): String =
        "\"${term.replace("\"", "\"\"")}\""

    private fun loadRecords(
        connection: Connection,
        sql: String,
        parameters: List<Any>,
    ): List<ChatMessageRecord> {
        return connection.prepareStatement(sql).use { statement ->
            bindParameters(statement, parameters)
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) add(results.toRecord())
                }
            }
        }
    }

    private fun bindParameters(
        statement: java.sql.PreparedStatement,
        parameters: List<Any>,
    ) {
        parameters.forEachIndexed { index, value ->
            when (value) {
                is Int -> statement.setInt(index + 1, value)
                is Long -> statement.setLong(index + 1, value)
                is String -> statement.setString(index + 1, value)
                else -> error("不支持的查询参数类型 ${value::class}")
            }
        }
    }

    private fun hasSearchIndex(connection: Connection): Boolean {
        connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1"
        ).use { statement ->
            statement.setString(1, SEARCH_FTS_TABLE)
            statement.executeQuery().use { results ->
                return results.next()
            }
        }
    }

    private fun readMeta(connection: Connection, key: String): String? {
        connection.prepareStatement(
            "SELECT value FROM chat_history_meta WHERE key = ?"
        ).use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { results ->
                return if (results.next()) results.getString(1) else null
            }
        }
    }

    private fun writeMeta(connection: Connection, key: String, value: String) {
        connection.prepareStatement(
            """
            INSERT INTO chat_history_meta(key, value) VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, key)
            statement.setString(2, value)
            statement.executeUpdate()
        }
    }

    private fun markSearchIndexIncomplete() {
        runCatching {
            withWriteConnection { connection ->
                writeMeta(connection, SEARCH_INDEX_COMPLETE_KEY, "0")
            }
        }.onFailure { warn("标记聊天记录全文索引未完成失败", it) }
    }

    private fun String.escapeLike(): String =
        replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun String.aliasMatchRank(query: String): Int = when {
        equals(query, ignoreCase = true) -> 0
        startsWith(query, ignoreCase = true) -> 1
        contains(query, ignoreCase = true) -> 2
        else -> Int.MAX_VALUE
    }

    private fun ResultSet.toRecord(): ChatMessageRecord {
        val kindOrdinal = getInt("kind")
        val kind = MessageSourceKind.values().getOrNull(kindOrdinal)
            ?: error("未知的消息类型序号 $kindOrdinal")
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

    private fun warn(message: String, cause: Throwable? = null) {
        runCatching { warningLogger?.invoke(message, cause) }
    }
}
