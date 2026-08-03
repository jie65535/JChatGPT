package top.jie65535.mirai.data

import org.sqlite.SQLiteConfig
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

data class ContactSnapshotBatch(
    val botId: Long,
    val capturedAt: Long,
    val users: List<ContactUserSnapshot> = emptyList(),
    val friends: List<Long> = emptyList(),
    val groups: List<ContactGroupSnapshot> = emptyList(),
    val members: List<ContactGroupMemberSnapshot> = emptyList(),
    val completeFriendList: Boolean = false,
    val completeGroupList: Boolean = false,
    val completeMemberGroupIds: Set<Long> = emptySet(),
)

data class ContactUserSnapshot(
    val botId: Long,
    val userId: Long,
    val nickname: String = "",
    val remark: String = "",
    val sex: String = "",
    val age: Int = 0,
    val qLevel: Int = 0,
    val email: String = "",
    val sign: String = "",
    val updatedAt: Long,
)

data class ContactGroupSnapshot(
    val botId: Long,
    val groupId: Long,
    val name: String = "",
    val memberCount: Int = 0,
    val maxMemberCount: Int = 0,
    val updatedAt: Long,
)

data class ContactGroupMemberSnapshot(
    val botId: Long,
    val groupId: Long,
    val userId: Long,
    val nickname: String = "",
    val nameCard: String = "",
    val role: String = "",
    val specialTitle: String = "",
    val sex: String = "",
    val age: Int = 0,
    val area: String = "",
    val level: Int = 0,
    val qLevel: Int = 0,
    val joinTime: Int = 0,
    val lastSpeakTime: Int = 0,
    val updatedAt: Long,
)

data class ContactProfileHint(
    val userId: Long,
    val nickname: String = "",
    val remark: String = "",
    val sex: String = "",
    val age: Int = 0,
    val qLevel: Int = 0,
    val sign: String = "",
    val isFriend: Boolean = false,
    val memberships: List<ContactGroupMemberHint> = emptyList(),
) {
    val displayName: String
        get() = memberships.asSequence().map(ContactGroupMemberHint::nameCard).firstOrNull(String::isNotBlank)
            ?: remark.takeIf(String::isNotBlank)
            ?: nickname
}

data class ContactGroupMemberHint(
    val groupId: Long,
    val groupName: String = "",
    val nickname: String = "",
    val nameCard: String = "",
    val role: String = "",
    val specialTitle: String = "",
    val sex: String = "",
    val age: Int = 0,
    val area: String = "",
    val level: Int = 0,
    val qLevel: Int = 0,
    val joinTime: Int = 0,
    val lastSpeakTime: Int = 0,
)

data class ContactNameMatch(
    val userId: Long,
    val displayName: String,
    val matchRank: Int,
)

object ContactSnapshotStore {
    private const val SCHEMA_VERSION = 1
    private const val BUSY_TIMEOUT_MS = 30_000
    private const val DATABASE_NAME = "chat-history.sqlite"

    private val lifecycleLock = Any()
    private val writeLock = Any()

    @Volatile
    private var initialized = false
    private lateinit var databaseFile: File
    private var writeConnection: Connection? = null

    val isAvailable: Boolean
        get() = initialized

    fun init(dataFolder: File) {
        synchronized(lifecycleLock) {
            if (initialized) return
            Class.forName("org.sqlite.JDBC")
            dataFolder.mkdirs()
            databaseFile = dataFolder.resolve(DATABASE_NAME)

            val connection = openConnection(databaseFile)
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
                writeConnection?.close()
                writeConnection = null
                initialized = false
            }
        }
    }

    fun save(batch: ContactSnapshotBatch) {
        if (!initialized) return
        withWriteConnection { connection ->
            val oldAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                upsertUsers(connection, batch.users)
                upsertFriends(connection, batch.botId, batch.friends, batch.capturedAt)
                upsertGroups(connection, batch.groups)
                upsertMembers(connection, batch.members)
                reconcileSnapshot(connection, batch)
                connection.commit()
            } catch (cause: Throwable) {
                connection.rollback()
                throw cause
            } finally {
                connection.autoCommit = oldAutoCommit
            }
        }
    }

    fun loadProfileHints(
        databaseFile: File,
        botId: Long,
        groupIds: Collection<Long>,
        userIds: Collection<Long>,
    ): Map<Long, ContactProfileHint> {
        if (userIds.isEmpty() || !databaseFile.isFile) return emptyMap()
        Class.forName("org.sqlite.JDBC")
        return openReadConnection(databaseFile).use { connection ->
            if (!hasContactSchema(connection)) return@use emptyMap()
            val userSet = userIds.toSet()
            val userSnapshots = queryUsers(connection, botId, userSet)
            val friendIds = queryFriendIds(connection, botId, userSet)
            val memberships = if (groupIds.isEmpty()) {
                emptyMap()
            } else {
                queryMemberships(connection, botId, groupIds.toSet(), userSet)
            }

            userSet.mapNotNull { userId ->
                val user = userSnapshots[userId]
                val memberHints = memberships[userId].orEmpty()
                if (user == null && memberHints.isEmpty() && userId !in friendIds) {
                    null
                } else {
                    userId to ContactProfileHint(
                        userId = userId,
                        nickname = user?.nickname.orEmpty(),
                        remark = user?.remark.orEmpty(),
                        sex = user?.sex.orEmpty(),
                        age = user?.age ?: 0,
                        qLevel = user?.qLevel ?: 0,
                        sign = user?.sign.orEmpty(),
                        isFriend = userId in friendIds,
                        memberships = memberHints,
                    )
                }
            }.toMap()
        }
    }

    fun loadProfileHints(
        botId: Long,
        groupIds: Collection<Long>,
        userIds: Collection<Long>,
    ): Map<Long, ContactProfileHint> {
        if (!initialized) return emptyMap()
        return loadProfileHints(databaseFile, botId, groupIds, userIds)
    }

    fun loadDisplayNames(
        botId: Long,
        groupId: Long?,
        userIds: Collection<Long>,
    ): Map<Long, String> {
        if (!initialized) return emptyMap()
        val groups = groupId?.let(::setOf).orEmpty()
        return loadProfileHints(databaseFile, botId, groups, userIds)
            .mapValues { (_, hint) -> hint.displayName }
            .filterValues(String::isNotBlank)
    }

    fun loadDisplayName(botId: Long, groupId: Long?, userId: Long): String? =
        loadDisplayNames(botId, groupId, listOf(userId))[userId]

    fun findUsersByName(
        botId: Long,
        groupId: Long?,
        query: String,
        limit: Int = 5,
    ): List<ContactNameMatch> {
        if (!initialized || query.isBlank() || limit <= 0) return emptyList()
        val normalizedQuery = query.trim()
        return openReadConnection(databaseFile).use { connection ->
            val sql = if (groupId != null) {
                """
                SELECT m.user_id, m.name_card, m.nickname AS member_nickname,
                       COALESCE(u.remark, '') AS remark,
                       COALESCE(u.nickname, '') AS user_nickname
                FROM contact_group_member_snapshot m
                LEFT JOIN contact_user_snapshot u
                  ON u.bot_id = m.bot_id AND u.user_id = m.user_id
                WHERE m.bot_id = ? AND m.group_id = ?
                """.trimIndent()
            } else {
                """
                SELECT f.user_id, '' AS name_card, '' AS member_nickname,
                       COALESCE(u.remark, '') AS remark,
                       COALESCE(u.nickname, '') AS user_nickname
                FROM contact_friend_snapshot f
                LEFT JOIN contact_user_snapshot u
                  ON u.bot_id = f.bot_id AND u.user_id = f.user_id
                WHERE f.bot_id = ?
                """.trimIndent()
            }
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, botId)
                if (groupId != null) statement.setLong(2, groupId)
                statement.executeQuery().use { results ->
                    buildList {
                        while (results.next()) {
                            val names = listOf(
                                results.getString("name_card").orEmpty(),
                                results.getString("remark").orEmpty(),
                                results.getString("member_nickname").orEmpty(),
                                results.getString("user_nickname").orEmpty(),
                            ).filter(String::isNotBlank)
                            val rank = names.minOfOrNull { name -> name.matchRank(normalizedQuery) }
                                ?.takeIf { it < Int.MAX_VALUE }
                                ?: continue
                            add(ContactNameMatch(
                                userId = results.getLong("user_id"),
                                displayName = names.firstOrNull().orEmpty(),
                                matchRank = rank,
                            ))
                        }
                    }.sortedWith(compareBy<ContactNameMatch>({ it.matchRank }, { it.userId }))
                        .distinctBy(ContactNameMatch::userId)
                        .take(limit)
                }
            }
        }
    }

    private fun upsertUsers(connection: Connection, users: List<ContactUserSnapshot>) {
        if (users.isEmpty()) return
        connection.prepareStatement(
            """
            INSERT INTO contact_user_snapshot(
                bot_id, user_id, nickname, remark, sex, age, q_level, email, sign, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(bot_id, user_id) DO UPDATE SET
                nickname = excluded.nickname,
                remark = excluded.remark,
                sex = CASE WHEN excluded.sex <> '' THEN excluded.sex ELSE contact_user_snapshot.sex END,
                age = CASE WHEN excluded.age > 0 THEN excluded.age ELSE contact_user_snapshot.age END,
                q_level = CASE WHEN excluded.q_level > 0 THEN excluded.q_level ELSE contact_user_snapshot.q_level END,
                email = CASE WHEN excluded.email <> '' THEN excluded.email ELSE contact_user_snapshot.email END,
                sign = CASE WHEN excluded.sign <> '' THEN excluded.sign ELSE contact_user_snapshot.sign END,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            users.forEach { user ->
                statement.setLong(1, user.botId)
                statement.setLong(2, user.userId)
                statement.setString(3, user.nickname)
                statement.setString(4, user.remark)
                statement.setString(5, user.sex)
                statement.setInt(6, user.age)
                statement.setInt(7, user.qLevel)
                statement.setString(8, user.email)
                statement.setString(9, user.sign)
                statement.setLong(10, user.updatedAt)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun upsertFriends(connection: Connection, botId: Long, friendIds: List<Long>, updatedAt: Long) {
        if (friendIds.isEmpty()) return
        connection.prepareStatement(
            """
            INSERT INTO contact_friend_snapshot(bot_id, user_id, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(bot_id, user_id) DO UPDATE SET
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            friendIds.distinct().forEach { userId ->
                statement.setLong(1, botId)
                statement.setLong(2, userId)
                statement.setLong(3, updatedAt)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun upsertGroups(connection: Connection, groups: List<ContactGroupSnapshot>) {
        if (groups.isEmpty()) return
        connection.prepareStatement(
            """
            INSERT INTO contact_group_snapshot(
                bot_id, group_id, name, member_count, max_member_count, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(bot_id, group_id) DO UPDATE SET
                name = excluded.name,
                member_count = excluded.member_count,
                max_member_count = excluded.max_member_count,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            groups.forEach { group ->
                statement.setLong(1, group.botId)
                statement.setLong(2, group.groupId)
                statement.setString(3, group.name)
                statement.setInt(4, group.memberCount)
                statement.setInt(5, group.maxMemberCount)
                statement.setLong(6, group.updatedAt)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun upsertMembers(connection: Connection, members: List<ContactGroupMemberSnapshot>) {
        if (members.isEmpty()) return
        connection.prepareStatement(
            """
            INSERT INTO contact_group_member_snapshot(
                bot_id, group_id, user_id, nickname, name_card, role, special_title,
                sex, age, area, level, q_level, join_time, last_speak_time, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(bot_id, group_id, user_id) DO UPDATE SET
                nickname = excluded.nickname,
                name_card = excluded.name_card,
                role = excluded.role,
                special_title = excluded.special_title,
                sex = excluded.sex,
                age = excluded.age,
                area = excluded.area,
                level = excluded.level,
                q_level = excluded.q_level,
                join_time = excluded.join_time,
                last_speak_time = excluded.last_speak_time,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            members.forEach { member ->
                statement.setLong(1, member.botId)
                statement.setLong(2, member.groupId)
                statement.setLong(3, member.userId)
                statement.setString(4, member.nickname)
                statement.setString(5, member.nameCard)
                statement.setString(6, member.role)
                statement.setString(7, member.specialTitle)
                statement.setString(8, member.sex)
                statement.setInt(9, member.age)
                statement.setString(10, member.area)
                statement.setInt(11, member.level)
                statement.setInt(12, member.qLevel)
                statement.setInt(13, member.joinTime)
                statement.setInt(14, member.lastSpeakTime)
                statement.setLong(15, member.updatedAt)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun reconcileSnapshot(connection: Connection, batch: ContactSnapshotBatch) {
        if (batch.completeFriendList) {
            connection.prepareStatement(
                "DELETE FROM contact_friend_snapshot WHERE bot_id = ? AND updated_at <> ?"
            ).use { statement ->
                statement.setLong(1, batch.botId)
                statement.setLong(2, batch.capturedAt)
                statement.executeUpdate()
            }
        }

        if (batch.completeGroupList) {
            connection.prepareStatement(
                "DELETE FROM contact_group_snapshot WHERE bot_id = ? AND updated_at <> ?"
            ).use { statement ->
                statement.setLong(1, batch.botId)
                statement.setLong(2, batch.capturedAt)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                DELETE FROM contact_group_member_snapshot
                WHERE bot_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM contact_group_snapshot g
                      WHERE g.bot_id = contact_group_member_snapshot.bot_id
                        AND g.group_id = contact_group_member_snapshot.group_id
                  )
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, batch.botId)
                statement.executeUpdate()
            }
        }

        if (batch.completeMemberGroupIds.isNotEmpty()) {
            connection.prepareStatement(
                """
                DELETE FROM contact_group_member_snapshot
                WHERE bot_id = ? AND group_id = ? AND updated_at <> ?
                """.trimIndent()
            ).use { statement ->
                batch.completeMemberGroupIds.forEach { groupId ->
                    statement.setLong(1, batch.botId)
                    statement.setLong(2, groupId)
                    statement.setLong(3, batch.capturedAt)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private fun queryUsers(
        connection: Connection,
        botId: Long,
        userIds: Set<Long>,
    ): Map<Long, ContactUserSnapshot> {
        val placeholders = userIds.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
            SELECT bot_id, user_id, nickname, remark, sex, age, q_level, email, sign, updated_at
            FROM contact_user_snapshot
            WHERE bot_id = ? AND user_id IN ($placeholders)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, botId)
            userIds.forEachIndexed { index, userId -> statement.setLong(index + 2, userId) }
            statement.executeQuery().use { results ->
                buildMap {
                    while (results.next()) {
                        val user = results.toUserSnapshot()
                        put(user.userId, user)
                    }
                }
            }
        }
    }

    private fun queryFriendIds(
        connection: Connection,
        botId: Long,
        userIds: Set<Long>,
    ): Set<Long> {
        val placeholders = userIds.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
            SELECT user_id
            FROM contact_friend_snapshot
            WHERE bot_id = ? AND user_id IN ($placeholders)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, botId)
            userIds.forEachIndexed { index, userId -> statement.setLong(index + 2, userId) }
            statement.executeQuery().use { results ->
                buildSet {
                    while (results.next()) add(results.getLong("user_id"))
                }
            }
        }
    }

    private fun queryMemberships(
        connection: Connection,
        botId: Long,
        groupIds: Set<Long>,
        userIds: Set<Long>,
    ): Map<Long, List<ContactGroupMemberHint>> {
        val groupPlaceholders = groupIds.joinToString(",") { "?" }
        val userPlaceholders = userIds.joinToString(",") { "?" }
        return connection.prepareStatement(
            """
            SELECT m.group_id, m.user_id, m.nickname, m.name_card, m.role, m.special_title,
                   m.sex, m.age, m.area, m.level, m.q_level, m.join_time, m.last_speak_time,
                   g.name AS group_name
            FROM contact_group_member_snapshot m
            LEFT JOIN contact_group_snapshot g
              ON g.bot_id = m.bot_id AND g.group_id = m.group_id
            WHERE m.bot_id = ?
              AND m.group_id IN ($groupPlaceholders)
              AND m.user_id IN ($userPlaceholders)
            ORDER BY m.updated_at DESC, m.group_id ASC
            """.trimIndent()
        ).use { statement ->
            var index = 1
            statement.setLong(index++, botId)
            groupIds.forEach { groupId -> statement.setLong(index++, groupId) }
            userIds.forEach { userId -> statement.setLong(index++, userId) }
            statement.executeQuery().use { results ->
                buildMap<Long, MutableList<ContactGroupMemberHint>> {
                    while (results.next()) {
                        val userId = results.getLong("user_id")
                        getOrPut(userId) { mutableListOf() } += ContactGroupMemberHint(
                            groupId = results.getLong("group_id"),
                            groupName = results.getString("group_name").orEmpty(),
                            nickname = results.getString("nickname").orEmpty(),
                            nameCard = results.getString("name_card").orEmpty(),
                            role = results.getString("role").orEmpty(),
                            specialTitle = results.getString("special_title").orEmpty(),
                            sex = results.getString("sex").orEmpty(),
                            age = results.getInt("age"),
                            area = results.getString("area").orEmpty(),
                            level = results.getInt("level"),
                            qLevel = results.getInt("q_level"),
                            joinTime = results.getInt("join_time"),
                            lastSpeakTime = results.getInt("last_speak_time"),
                        )
                    }
                }
            }
        }
    }

    private fun withWriteConnection(block: (Connection) -> Unit) {
        synchronized(writeLock) {
            check(initialized) { "联系人快照数据库尚未初始化" }
            val connection = writeConnection?.takeUnless(Connection::isClosed)
                ?: openConnection(databaseFile).also {
                    configureWriteConnection(it)
                    writeConnection = it
                }
            block(connection)
        }
    }

    private fun createSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS contact_user_snapshot(
                    bot_id INTEGER NOT NULL,
                    user_id INTEGER NOT NULL,
                    nickname TEXT NOT NULL DEFAULT '',
                    remark TEXT NOT NULL DEFAULT '',
                    sex TEXT NOT NULL DEFAULT '',
                    age INTEGER NOT NULL DEFAULT 0,
                    q_level INTEGER NOT NULL DEFAULT 0,
                    email TEXT NOT NULL DEFAULT '',
                    sign TEXT NOT NULL DEFAULT '',
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(bot_id, user_id)
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS contact_friend_snapshot(
                    bot_id INTEGER NOT NULL,
                    user_id INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(bot_id, user_id)
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS contact_group_snapshot(
                    bot_id INTEGER NOT NULL,
                    group_id INTEGER NOT NULL,
                    name TEXT NOT NULL DEFAULT '',
                    member_count INTEGER NOT NULL DEFAULT 0,
                    max_member_count INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(bot_id, group_id)
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS contact_group_member_snapshot(
                    bot_id INTEGER NOT NULL,
                    group_id INTEGER NOT NULL,
                    user_id INTEGER NOT NULL,
                    nickname TEXT NOT NULL DEFAULT '',
                    name_card TEXT NOT NULL DEFAULT '',
                    role TEXT NOT NULL DEFAULT '',
                    special_title TEXT NOT NULL DEFAULT '',
                    sex TEXT NOT NULL DEFAULT '',
                    age INTEGER NOT NULL DEFAULT 0,
                    area TEXT NOT NULL DEFAULT '',
                    level INTEGER NOT NULL DEFAULT 0,
                    q_level INTEGER NOT NULL DEFAULT 0,
                    join_time INTEGER NOT NULL DEFAULT 0,
                    last_speak_time INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(bot_id, group_id, user_id)
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_contact_member_user " +
                        "ON contact_group_member_snapshot(bot_id, user_id, updated_at DESC)"
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS contact_snapshot_meta(
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
        connection.prepareStatement(
            "INSERT INTO contact_snapshot_meta(key, value) VALUES ('schema_version', ?) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value"
        ).use { statement ->
            statement.setString(1, SCHEMA_VERSION.toString())
            statement.executeUpdate()
        }
    }

    private fun hasContactSchema(connection: Connection): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'contact_user_snapshot' LIMIT 1"
        ).use { statement ->
            statement.executeQuery().use(ResultSet::next)
        }

    private fun configureWriteConnection(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA synchronous=NORMAL")
            statement.execute("PRAGMA busy_timeout=$BUSY_TIMEOUT_MS")
            statement.execute("PRAGMA wal_autocheckpoint=1000")
        }
    }

    private fun openConnection(databaseFile: File): Connection =
        DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}")

    private fun openReadConnection(databaseFile: File): Connection {
        val config = SQLiteConfig().apply {
            setReadOnly(true)
            setBusyTimeout(BUSY_TIMEOUT_MS)
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

    private fun ResultSet.toUserSnapshot(): ContactUserSnapshot = ContactUserSnapshot(
        botId = getLong("bot_id"),
        userId = getLong("user_id"),
        nickname = getString("nickname").orEmpty(),
        remark = getString("remark").orEmpty(),
        sex = getString("sex").orEmpty(),
        age = getInt("age"),
        qLevel = getInt("q_level"),
        email = getString("email").orEmpty(),
        sign = getString("sign").orEmpty(),
        updatedAt = getLong("updated_at"),
    )

    private fun String.matchRank(query: String): Int = when {
        equals(query, ignoreCase = true) -> 0
        startsWith(query, ignoreCase = true) -> 1
        contains(query, ignoreCase = true) -> 2
        else -> Int.MAX_VALUE
    }
}
