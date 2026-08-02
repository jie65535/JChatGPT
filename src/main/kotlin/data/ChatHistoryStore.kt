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
    private const val SCHEMA_VERSION = 2
    private const val BUSY_TIMEOUT_MS = 30_000

    private val lifecycleLock = Any()
    private val writeLock = Any()

    @Volatile
    private var initialized = false
    private lateinit var databaseFile: File
    private var writeConnection: Connection? = null
    private var warningLogger: ((String, Throwable?) -> Unit)? = null

    val isAvailable: Boolean
        get() = initialized

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
                warningLogger = null
            }
        }
    }

    fun record(event: MessageEvent) {
        if (!initialized) return
        val message = event.message.asSequence()
            .filterNot { it is MessageSource }
            .toMessageChain()
        insert(ChatMessageRecord.fromSuccess(event.message.source, message))
    }

    fun record(event: MessagePostSendEvent<*>) {
        if (!initialized) return
        val source = event.receipt?.source ?: return
        val message = event.message.asSequence()
            .filterNot { it is MessageSource }
            .toMessageChain()
        insert(ChatMessageRecord.fromSuccess(source, message))
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
        limit: Int,
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
            append(" ORDER BY time DESC, id DESC LIMIT ?")
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
                statement.setInt(parameters.size + 1, limit.coerceAtLeast(1))
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

    private fun insert(record: ChatMessageRecord) {
        withWriteConnection { connection ->
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
        }
    }

    private fun withWriteConnection(block: (Connection) -> Unit) {
        synchronized(writeLock) {
            check(initialized) { "聊天记录数据库尚未初始化" }
            val connection = writeConnection?.takeUnless(Connection::isClosed)
                ?: openConnection().also {
                    configureWriteConnection(it)
                    writeConnection = it
                }
            block(connection)
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
        }
        connection.prepareStatement(
            "INSERT INTO chat_history_meta(key, value) VALUES ('schema_version', ?) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value"
        ).use { statement ->
            statement.setString(1, SCHEMA_VERSION.toString())
            statement.executeUpdate()
        }
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
