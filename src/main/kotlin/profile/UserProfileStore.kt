package top.jie65535.mirai.profile

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

object UserProfileStore {
    private const val SCHEMA_VERSION = 4
    private const val BUSY_TIMEOUT_MS = 30_000

    private val lifecycleLock = Any()
    private val writeLock = Any()
    private val json = Json { encodeDefaults = true }
    private val operationListSerializer = ListSerializer(AppliedProfileOperation.serializer())

    @Volatile
    private var initialized = false
    private lateinit var databaseFile: File
    private var writeConnection: Connection? = null

    @Volatile
    private var lastBackupFile: File? = null

    val isAvailable: Boolean
        get() = initialized

    val lastBackupFileOrNull: File?
        get() = lastBackupFile

    fun init(dataFolder: File) {
        synchronized(lifecycleLock) {
            if (initialized) return
            Class.forName("org.sqlite.JDBC")
            dataFolder.mkdirs()
            databaseFile = dataFolder.resolve("user-profile.sqlite")
            val connection = openConnection()
            try {
                configureWriteConnection(connection)
                lastBackupFile = null
                lastBackupFile = ProfileDatabaseReset.backupLegacyDatabase(connection, databaseFile)
                createSchema(connection, resetLegacy = lastBackupFile != null)
                if (lastBackupFile != null) {
                    connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
                }
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
                    }
                    connection.close()
                }
                writeConnection = null
                initialized = false
            }
        }
    }

    fun load(groupId: Long, userId: Long): UserProfileSnapshot? {
        check(initialized) { "用户画像数据库尚未初始化" }
        return openReadConnection().use { connection ->
            val profileRow = connection.prepareStatement(
                """
                SELECT group_id, user_id, summary, version, cursor_time, snapshot_end_time,
                       reliable, model, prompt_version, updated_at
                FROM user_profile
                WHERE user_id = ? AND group_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, groupId)
                statement.executeQuery().use { results ->
                    if (results.next()) results.toProfileWithoutItems() else null
                }
            } ?: return@use null

            val items = connection.prepareStatement(
                """
                SELECT item_id, category, content, confidence, related_user_id,
                       first_seen_at, last_confirmed_at
                FROM profile_item
                WHERE user_id = ? AND group_id = ?
                ORDER BY category, first_seen_at, item_id
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, groupId)
                statement.executeQuery().use { results ->
                    buildList {
                        while (results.next()) add(results.toProfileItem())
                    }
                }
            }
            profileRow.copy(items = items)
        }
    }

    fun listUserIds(groupId: Long): List<Long> {
        check(initialized) { "用户画像数据库尚未初始化" }
        return openReadConnection().use { connection ->
            connection.prepareStatement("SELECT user_id FROM user_profile WHERE group_id = ? ORDER BY user_id").use { statement ->
                statement.setLong(1, groupId)
                statement.executeQuery().use { results ->
                    buildList {
                        while (results.next()) add(results.getLong("user_id"))
                    }
                }
            }
        }
    }

    fun listUserIdsWithMinimumItems(groupId: Long, minimumItems: Int): List<Long> {
        check(initialized) { "用户画像数据库尚未初始化" }
        require(minimumItems > 0) { "minimumItems must be positive" }
        return openReadConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT user_id
                FROM profile_item
                WHERE group_id = ?
                GROUP BY user_id
                HAVING COUNT(*) >= ?
                ORDER BY user_id
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, groupId)
                statement.setInt(2, minimumItems)
                statement.executeQuery().use { results ->
                    buildList {
                        while (results.next()) add(results.getLong("user_id"))
                    }
                }
            }
        }
    }

    fun loadSupportStats(groupId: Long, userId: Long): Map<String, ProfileItemSupportStats> {
        check(initialized) { "用户画像数据库尚未初始化" }
        return openReadConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT item_id, COUNT(*) AS support_count,
                       MIN(start_time) AS first_supported_at,
                       MAX(end_time) AS last_supported_at
                FROM profile_support
                WHERE user_id = ? AND group_id = ?
                GROUP BY item_id
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, groupId)
                statement.executeQuery().use { results ->
                    buildMap {
                        while (results.next()) {
                            put(
                                results.getString("item_id"),
                                ProfileItemSupportStats(
                                    count = results.getInt("support_count"),
                                    firstSupportedAt = results.getInt("first_supported_at"),
                                    lastSupportedAt = results.getInt("last_supported_at"),
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    fun commit(
        reduction: ProfileReduction,
        batch: ProfileHistoryBatch,
        usage: ProfileTokenUsage,
        source: ProfileRevisionSource = ProfileRevisionSource.BACKFILL,
    ) = commitAll(listOf(reduction to batch), usage, source)

    fun commitConversation(
        reductions: List<Pair<ProfileReduction, ProfileHistoryBatch>>,
        usage: ProfileTokenUsage,
    ) = commitAll(reductions, usage, ProfileRevisionSource.CONVERSATION)

    fun commitCompaction(
        plan: ProfileCompactionPlan,
        batch: ProfileHistoryBatch,
        usage: ProfileTokenUsage,
    ) = commitAll(
        entries = listOf(plan.reduction to batch),
        usage = usage,
        source = ProfileRevisionSource.COMPACTION,
        supportReassignments = plan.supportReassignments,
    )

    fun isConversationProcessed(groupId: Long, inputHash: String): Boolean {
        check(initialized) { "用户画像数据库尚未初始化" }
        return openReadConnection().use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM profile_revision WHERE source = 'conversation' AND input_hash = ? AND group_id = ? LIMIT 1"
            ).use { statement ->
                statement.setString(1, inputHash)
                statement.setLong(2, groupId)
                statement.executeQuery().use { it.next() }
            }
        }
    }

    fun loadGroupCursor(botId: Long, groupId: Long): GroupProfileCursor? {
        check(initialized) { "用户画像数据库尚未初始化" }
        return openReadConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT bot_id, group_id, cursor_time, snapshot_end_time, updated_at
                FROM profile_group_cursor
                WHERE bot_id = ? AND group_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, botId)
                statement.setLong(2, groupId)
                statement.executeQuery().use { results ->
                    if (!results.next()) return@use null
                    results.toGroupProfileCursor()
                }
            }
        }
    }

    fun loadGroupCursors(): List<GroupProfileCursor> {
        check(initialized) { "用户画像数据库尚未初始化" }
        return openReadConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT bot_id, group_id, cursor_time, snapshot_end_time, updated_at
                FROM profile_group_cursor
                ORDER BY bot_id, group_id
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { results ->
                    buildList {
                        while (results.next()) add(results.toGroupProfileCursor())
                    }
                }
            }
        }
    }

    fun saveGroupCursor(cursor: GroupProfileCursor) {
        check(initialized) { "用户画像数据库尚未初始化" }
        withWriteConnection { connection ->
            connection.prepareStatement(
                """
                INSERT INTO profile_group_cursor(
                    bot_id, group_id, cursor_time, snapshot_end_time, updated_at
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(bot_id, group_id) DO UPDATE SET
                    cursor_time = excluded.cursor_time,
                    snapshot_end_time = excluded.snapshot_end_time,
                    updated_at = excluded.updated_at
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, cursor.botId)
                statement.setLong(2, cursor.groupId)
                statement.setInt(3, cursor.cursorTime)
                statement.setInt(4, cursor.snapshotEndTime)
                statement.setLong(5, cursor.updatedAt)
                statement.executeUpdate()
            }
        }
    }

    private fun commitAll(
        entries: List<Pair<ProfileReduction, ProfileHistoryBatch>>,
        usage: ProfileTokenUsage,
        source: ProfileRevisionSource,
        supportReassignments: Map<String, String> = emptyMap(),
    ) {
        check(initialized) { "用户画像数据库尚未初始化" }
        if (entries.isEmpty()) return
        withWriteConnection { connection ->
            val oldAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                entries.forEachIndexed { index, (reduction, batch) ->
                    persist(
                        connection = connection,
                        reduction = reduction,
                        batch = batch,
                        usage = if (index == 0) usage else ProfileTokenUsage(),
                        source = source,
                    )
                }
                if (supportReassignments.isNotEmpty()) {
                    val userId = entries.single().first.profile.userId
                    connection.prepareStatement(
                        "UPDATE profile_support SET item_id = ? WHERE user_id = ? AND group_id = ? AND item_id = ?"
                    ).use { statement ->
                        supportReassignments.forEach { (sourceItemId, targetItemId) ->
                            statement.setString(1, targetItemId)
                            statement.setLong(2, userId)
                            statement.setLong(3, entries.single().first.profile.groupId)
                            statement.setString(4, sourceItemId)
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                }
                connection.commit()
            } catch (cause: Throwable) {
                connection.rollback()
                throw cause
            } finally {
                connection.autoCommit = oldAutoCommit
            }
        }
    }

    private fun persist(
        connection: Connection,
        reduction: ProfileReduction,
        batch: ProfileHistoryBatch,
        usage: ProfileTokenUsage,
        source: ProfileRevisionSource,
    ) {
        val profile = reduction.profile
        require(profile.groupId > 0 && profile.userId > 0) { "群号和用户 ID 必须是正数" }
        require(profile.groupId == batch.groupId && profile.userId == batch.userId) { "画像与证据批次范围不一致" }
        require(batch.messages.all { it.record.targetId == profile.groupId }) { "证据包含其他群的消息" }
        connection.prepareStatement(
            """
            INSERT INTO user_profile(
                user_id, group_id, summary, version, cursor_time, snapshot_end_time,
                reliable, model, prompt_version, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(group_id, user_id) DO UPDATE SET
                summary = excluded.summary,
                version = excluded.version,
                cursor_time = excluded.cursor_time,
                snapshot_end_time = excluded.snapshot_end_time,
                reliable = excluded.reliable,
                model = excluded.model,
                prompt_version = excluded.prompt_version,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, profile.userId)
            statement.setLong(2, profile.groupId)
            statement.setString(3, profile.summary)
            statement.setInt(4, profile.version)
            statement.setInt(5, profile.cursorTime)
            statement.setInt(6, profile.snapshotEndTime)
            statement.setInt(7, if (profile.reliable) 1 else 0)
            statement.setString(8, profile.model)
            statement.setString(9, profile.promptVersion)
            statement.setLong(10, profile.updatedAt)
            statement.executeUpdate()
        }

        connection.prepareStatement("DELETE FROM profile_item WHERE user_id = ? AND group_id = ?").use { statement ->
            statement.setLong(1, profile.userId)
            statement.setLong(2, profile.groupId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO profile_item(
                item_id, user_id, group_id, category, content, confidence, related_user_id,
                first_seen_at, last_confirmed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            profile.items.forEach { item ->
                statement.setString(1, item.id)
                statement.setLong(2, profile.userId)
                statement.setLong(3, profile.groupId)
                statement.setString(4, item.category.toStorageValue())
                statement.setString(5, item.content)
                statement.setString(6, item.confidence.toStorageValue())
                if (item.relatedUserId == null) statement.setNull(7, java.sql.Types.BIGINT)
                else statement.setLong(7, item.relatedUserId)
                statement.setInt(8, item.firstSeenAt)
                statement.setInt(9, item.lastConfirmedAt)
                statement.addBatch()
            }
            statement.executeBatch()
        }

        if (source != ProfileRevisionSource.COMPACTION) {
            connection.prepareStatement(
                """
                INSERT INTO profile_support(
                    item_id, user_id, group_id, group_ids, start_time, end_time,
                    input_hash, action, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                reduction.operations.forEach { operation ->
                    val evidence = operation.evidenceRefs.mapNotNull(batch.evidenceByRef::get)
                    val startTime = evidence.minOf { it.record.time }
                    val endTime = evidence.maxOf { it.record.time }.safeNextSecond()
                    val groupIds = evidence.map { it.record.targetId }.distinct().sorted().joinToString(",")
                    statement.setString(1, operation.itemId)
                    statement.setLong(2, profile.userId)
                    statement.setLong(3, profile.groupId)
                    statement.setString(4, groupIds)
                    statement.setInt(5, startTime)
                    statement.setInt(6, endTime)
                    statement.setString(7, batch.inputHash)
                    statement.setString(8, operation.action.name)
                    statement.setLong(9, System.currentTimeMillis())
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }

        connection.prepareStatement(
            """
            INSERT INTO profile_revision(
                user_id, group_id, profile_version, source, start_time, end_time, input_hash,
                operations_json, summary, model, prompt_version,
                prompt_tokens, completion_tokens, cached_tokens, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, profile.userId)
            statement.setLong(2, profile.groupId)
            statement.setInt(3, profile.version)
            statement.setString(4, source.name.lowercase())
            statement.setInt(5, batch.startTime)
            statement.setInt(6, batch.endTime)
            statement.setString(7, batch.inputHash)
            statement.setString(8, json.encodeToString(operationListSerializer, reduction.operations))
            statement.setString(9, profile.summary)
            statement.setString(10, profile.model)
            statement.setString(11, profile.promptVersion)
            statement.setInt(12, usage.promptTokens)
            statement.setInt(13, usage.completionTokens)
            statement.setInt(14, usage.cachedTokens)
            statement.setLong(15, System.currentTimeMillis())
            statement.executeUpdate()
        }
    }

    fun lastRevisionAt(groupId: Long, userId: Long, source: ProfileRevisionSource): Long? {
        check(initialized) { "用户画像数据库尚未初始化" }
        return openReadConnection().use { connection ->
            connection.prepareStatement(
                "SELECT MAX(created_at) FROM profile_revision WHERE user_id = ? AND group_id = ? AND source = ?"
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, groupId)
                statement.setString(3, source.name.lowercase())
                statement.executeQuery().use { results ->
                    if (!results.next()) return@use null
                    results.getLong(1).let { if (results.wasNull()) null else it }
                }
            }
        }
    }

    private fun createSchema(connection: Connection, resetLegacy: Boolean) {
        val oldAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                if (resetLegacy) {
                    ProfileDatabaseReset.PROFILE_TABLES.forEach { table ->
                        statement.executeUpdate("DROP TABLE IF EXISTS $table")
                    }
                }
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS user_profile(
                        user_id INTEGER NOT NULL,
                        group_id INTEGER NOT NULL,
                        summary TEXT NOT NULL,
                        version INTEGER NOT NULL,
                        cursor_time INTEGER NOT NULL,
                        snapshot_end_time INTEGER NOT NULL,
                        reliable INTEGER NOT NULL,
                        model TEXT NOT NULL,
                        prompt_version TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(group_id, user_id)
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS profile_item(
                        item_id TEXT NOT NULL,
                        user_id INTEGER NOT NULL,
                        group_id INTEGER NOT NULL,
                        category TEXT NOT NULL,
                        content TEXT NOT NULL,
                        confidence TEXT NOT NULL,
                        related_user_id INTEGER,
                        first_seen_at INTEGER NOT NULL,
                        last_confirmed_at INTEGER NOT NULL,
                        PRIMARY KEY(group_id, user_id, item_id)
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_profile_item_user ON profile_item(group_id, user_id, category)"
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS profile_support(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        item_id TEXT NOT NULL,
                        user_id INTEGER NOT NULL,
                        group_id INTEGER NOT NULL,
                        group_ids TEXT NOT NULL,
                        start_time INTEGER NOT NULL,
                        end_time INTEGER NOT NULL,
                        input_hash TEXT NOT NULL,
                        action TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_profile_support_user ON profile_support(group_id, user_id, start_time)"
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS profile_revision(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id INTEGER NOT NULL,
                        group_id INTEGER NOT NULL,
                        profile_version INTEGER NOT NULL,
                        source TEXT NOT NULL DEFAULT 'backfill',
                        start_time INTEGER NOT NULL,
                        end_time INTEGER NOT NULL,
                        input_hash TEXT NOT NULL,
                        operations_json TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        model TEXT NOT NULL,
                        prompt_version TEXT NOT NULL,
                        prompt_tokens INTEGER NOT NULL,
                        completion_tokens INTEGER NOT NULL,
                        cached_tokens INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        UNIQUE(group_id, user_id, start_time, end_time, input_hash)
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_profile_revision_conversation " +
                            "ON profile_revision(group_id, source, input_hash)"
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS user_profile_meta(
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS profile_group_cursor(
                        bot_id INTEGER NOT NULL,
                        group_id INTEGER NOT NULL,
                        cursor_time INTEGER NOT NULL,
                        snapshot_end_time INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(bot_id, group_id)
                    )
                    """.trimIndent()
                )
            }
            connection.prepareStatement(
                "INSERT INTO user_profile_meta(key, value) VALUES ('schema_version', ?) " +
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
    }

    private fun configureWriteConnection(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA synchronous=NORMAL")
            statement.execute("PRAGMA busy_timeout=$BUSY_TIMEOUT_MS")
            statement.execute("PRAGMA wal_autocheckpoint=1000")
        }
    }

    private fun openConnection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}")

    private fun openReadConnection(): Connection = openConnection().also { connection ->
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA busy_timeout=$BUSY_TIMEOUT_MS")
            statement.execute("PRAGMA query_only=ON")
        }
    }

    private fun withWriteConnection(block: (Connection) -> Unit) {
        synchronized(writeLock) {
            val connection = writeConnection?.takeUnless(Connection::isClosed)
                ?: openConnection().also {
                    configureWriteConnection(it)
                    writeConnection = it
                }
            block(connection)
        }
    }

    private fun ResultSet.toProfileWithoutItems() = UserProfileSnapshot(
        userId = getLong("user_id"),
        groupId = getLong("group_id"),
        summary = getString("summary"),
        version = getInt("version"),
        cursorTime = getInt("cursor_time"),
        snapshotEndTime = getInt("snapshot_end_time"),
        reliable = getInt("reliable") != 0,
        model = getString("model"),
        promptVersion = getString("prompt_version"),
        updatedAt = getLong("updated_at"),
    )

    private fun ResultSet.toProfileItem() = UserProfileItem(
        id = getString("item_id"),
        category = ProfileCategory.valueOf(getString("category").uppercase()),
        content = getString("content"),
        confidence = ProfileConfidence.valueOf(getString("confidence").uppercase()),
        relatedUserId = getLong("related_user_id").let { if (wasNull()) null else it },
        firstSeenAt = getInt("first_seen_at"),
        lastConfirmedAt = getInt("last_confirmed_at"),
    )

    private fun ResultSet.toGroupProfileCursor() = GroupProfileCursor(
        botId = getLong("bot_id"),
        groupId = getLong("group_id"),
        cursorTime = getInt("cursor_time"),
        snapshotEndTime = getInt("snapshot_end_time"),
        updatedAt = getLong("updated_at"),
    )

    private fun ProfileCategory.toStorageValue(): String = name.lowercase()
    private fun ProfileConfidence.toStorageValue(): String = name.lowercase()
    private fun Int.safeNextSecond(): Int = if (this == Int.MAX_VALUE) this else this + 1
}
