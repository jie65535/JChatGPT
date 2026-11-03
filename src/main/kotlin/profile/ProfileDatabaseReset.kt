package top.jie65535.mirai.profile

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 旧全局画像无法可靠拆分到各群；先备份并验证，再由存储层事务性重置。 */
internal object ProfileDatabaseReset {
    val PROFILE_TABLES = listOf(
        "profile_support", "profile_item", "profile_revision", "user_profile",
        "profile_group_cursor", "user_profile_meta",
    )

    fun backupLegacyDatabase(connection: Connection, databaseFile: File): File? {
        val tables = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { results ->
                buildSet { while (results.next()) add(results.getString(1)) }
            }
        }
        if ("user_profile_meta" in tables) {
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT value FROM user_profile_meta WHERE key = 'schema_version'"
                ).use { results ->
                    if (results.next()) {
                        val version = results.getString(1).toIntOrNull()
                        check(version != null && version <= 4) { "不支持的用户画像数据库版本" }
                    }
                }
            }
        }
        val existingTables = PROFILE_TABLES.filter { it in tables }
        if (existingTables.isEmpty()) return null
        val scoped = "user_profile" in tables && connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(user_profile)").use { results ->
                var found = false
                while (results.next()) if (results.getString("name") == "group_id") found = true
                found
            }
        }
        if (scoped) return null

        val backupFolder = databaseFile.parentFile.resolve("profile-backups")
        check(backupFolder.isDirectory || backupFolder.mkdirs()) { "无法创建旧画像备份目录" }
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val backupFile = backupFolder.resolve("user-profile-before-group-$timestamp-${UUID.randomUUID()}.sqlite")
        // SQLite takes a consistent snapshot including committed WAL pages.
        connection.prepareStatement("VACUUM INTO ?").use { statement ->
            statement.setString(1, backupFile.absolutePath)
            statement.execute()
        }
        DriverManager.getConnection("jdbc:sqlite:${backupFile.absolutePath}").use { backup ->
            backup.createStatement().use { statement ->
                statement.execute("PRAGMA query_only=ON")
                statement.executeQuery("PRAGMA integrity_check").use { results ->
                    check(results.next() && results.getString(1) == "ok" && !results.next()) {
                        "旧画像备份完整性验证失败；未重置运行数据"
                    }
                }
            }
            existingTables.forEach { table ->
                check(rowCount(connection, table) == rowCount(backup, table)) {
                    "旧画像备份行数验证失败: $table；未重置运行数据"
                }
            }
        }
        // Do not retain deleted legacy contents in the active database's free pages.
        connection.createStatement().use { it.execute("PRAGMA secure_delete=ON") }
        return backupFile
    }

    private fun rowCount(connection: Connection, table: String): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { results ->
                check(results.next()) { "无法验证画像备份: $table" }
                results.getLong(1)
            }
        }
}
