package top.jie65535.mirai.profile

import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileDatabaseResetTest {
    @Test
    fun backsUpEveryLegacyVersionIncludingWalAndResetsAllProfileTables() {
        listOf(null, 1, 2, 3).forEach { version ->
            val directory = Files.createTempDirectory("profile-reset-$version-").toFile()
            try {
                val database = directory.resolve("user-profile.sqlite")
                connect(database.path).use { legacy ->
                    createLegacyDatabase(legacy, version)
                    // Leave a writer open so committed legacy rows are still in WAL.
                    UserProfileStore.init(directory)
                    val backup = assertNotNull(UserProfileStore.lastBackupFileOrNull)
                    assertTrue(backup.isFile)
                    connect(backup.path).use { saved ->
                        listOf("user_profile", "profile_item", "profile_support", "profile_revision").forEach { table ->
                            assertEquals(1, count(saved, table))
                        }
                        assertEquals(1, count(saved, "profile_group_cursor"))
                        saved.createStatement().use { statement ->
                            statement.executeQuery("SELECT summary FROM user_profile").use { results ->
                                assertTrue(results.next())
                                assertEquals("旧全局私密摘要", results.getString(1))
                            }
                        }
                    }
                    assertNull(UserProfileStore.load(300, 100))
                    assertTrue(UserProfileStore.loadGroupCursors().isEmpty())
                    assertTrue(UserProfileStore.loadSupportStats(300, 100).isEmpty())
                    assertTrue(!UserProfileStore.isConversationProcessed(300, "old-hash"))
                    UserProfileStore.close()
                }
                connect(database.path).use { fresh ->
                    ProfileDatabaseReset.PROFILE_TABLES.filter { it != "user_profile_meta" }.forEach { table ->
                        assertEquals(0, count(fresh, table))
                    }
                    fresh.createStatement().use { statement ->
                        statement.executeQuery("SELECT value FROM user_profile_meta WHERE key = 'schema_version'").use {
                            assertTrue(it.next())
                            assertEquals("4", it.getString(1))
                        }
                        statement.executeQuery("PRAGMA index_info(idx_profile_item_user)").use { results ->
                            assertTrue(results.next())
                            assertEquals("group_id", results.getString("name"))
                        }
                    }
                }
                UserProfileStore.init(directory)
                assertNull(UserProfileStore.lastBackupFileOrNull)
                assertEquals(1, directory.resolve("profile-backups").listFiles()!!.size)
            } finally {
                UserProfileStore.close()
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun backupFailureLeavesTheLegacyDataIntactAndStoreUnavailable() {
        val directory = Files.createTempDirectory("profile-backup-failure-").toFile()
        try {
            val database = directory.resolve("user-profile.sqlite")
            connect(database.path).use { createLegacyDatabase(it, 3) }
            directory.resolve("profile-backups").writeText("不能覆盖的文件")

            assertFails { UserProfileStore.init(directory) }
            assertTrue(!UserProfileStore.isAvailable)
            connect(database.path).use { legacy ->
                assertEquals(1, count(legacy, "user_profile"))
                assertEquals(1, count(legacy, "profile_revision"))
                assertEquals(1, count(legacy, "profile_group_cursor"))
            }
        } finally {
            UserProfileStore.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun resetFailureRollsBackTheSchemaAndKeepsTheVerifiedBackup() {
        val directory = Files.createTempDirectory("profile-reset-failure-").toFile()
        try {
            val database = directory.resolve("user-profile.sqlite")
            connect(database.path).use { legacy ->
                createLegacyDatabase(legacy, 3)
                legacy.createStatement().use { statement ->
                    statement.executeUpdate("DROP TABLE profile_item")
                    statement.executeUpdate("CREATE VIEW profile_item AS SELECT summary FROM user_profile")
                }
            }
            assertFails { UserProfileStore.init(directory) }
            assertTrue(!UserProfileStore.isAvailable)
            assertTrue(assertNotNull(UserProfileStore.lastBackupFileOrNull).isFile)
            connect(database.path).use { legacy ->
                assertEquals(1, count(legacy, "user_profile"))
                // profile_support is dropped first and must have been rolled back.
                assertEquals(1, count(legacy, "profile_support"))
            }
        } finally {
            UserProfileStore.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun refusesToResetAnUnknownFutureSchema() {
        val directory = Files.createTempDirectory("profile-future-schema-").toFile()
        try {
            val database = directory.resolve("user-profile.sqlite")
            connect(database.path).use { createLegacyDatabase(it, 999) }
            assertFails { UserProfileStore.init(directory) }
            assertTrue(!UserProfileStore.isAvailable)
            connect(database.path).use { assertEquals(1, count(it, "user_profile")) }
            assertTrue(!directory.resolve("profile-backups").exists())
        } finally {
            UserProfileStore.close()
            directory.deleteRecursively()
        }
    }

    private fun createLegacyDatabase(connection: Connection, version: Int?) {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA wal_autocheckpoint=0")
            statement.executeUpdate(
                """CREATE TABLE user_profile(
                    user_id INTEGER PRIMARY KEY, summary TEXT NOT NULL, version INTEGER NOT NULL,
                    cursor_time INTEGER NOT NULL, snapshot_end_time INTEGER NOT NULL,
                    reliable INTEGER NOT NULL, model TEXT NOT NULL, prompt_version TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )"""
            )
            statement.executeUpdate("INSERT INTO user_profile VALUES(100, '旧全局私密摘要', 7, 200, 1000, 1, 'test', 'v1', 123)")
            statement.executeUpdate(
                """CREATE TABLE profile_item(
                    item_id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, category TEXT NOT NULL,
                    content TEXT NOT NULL, confidence TEXT NOT NULL, related_user_id INTEGER,
                    first_seen_at INTEGER NOT NULL, last_confirmed_at INTEGER NOT NULL
                )"""
            )
            statement.executeUpdate("INSERT INTO profile_item VALUES('old-item', 100, 'interest', '旧条目', 'medium', NULL, 100, 150)")
            statement.executeUpdate("CREATE INDEX idx_profile_item_user ON profile_item(user_id, category)")
            statement.executeUpdate(
                """CREATE TABLE profile_support(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, item_id TEXT NOT NULL, user_id INTEGER NOT NULL,
                    group_ids TEXT NOT NULL, start_time INTEGER NOT NULL, end_time INTEGER NOT NULL,
                    input_hash TEXT NOT NULL, action TEXT NOT NULL, created_at INTEGER NOT NULL
                )"""
            )
            statement.executeUpdate("INSERT INTO profile_support VALUES(1, 'old-item', 100, '300,400', 100, 200, 'old-hash', 'ADD', 123)")
            val sourceColumn = if (version != null && version >= 2) "source TEXT NOT NULL DEFAULT 'backfill'," else ""
            statement.executeUpdate(
                """CREATE TABLE profile_revision(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, profile_version INTEGER NOT NULL,
                    $sourceColumn start_time INTEGER NOT NULL, end_time INTEGER NOT NULL, input_hash TEXT NOT NULL,
                    operations_json TEXT NOT NULL, summary TEXT NOT NULL, model TEXT NOT NULL, prompt_version TEXT NOT NULL,
                    prompt_tokens INTEGER NOT NULL, completion_tokens INTEGER NOT NULL, cached_tokens INTEGER NOT NULL,
                    created_at INTEGER NOT NULL, UNIQUE(user_id, start_time, end_time, input_hash)
                )"""
            )
            statement.executeUpdate(
                """INSERT INTO profile_revision(user_id, profile_version, start_time, end_time, input_hash,
                    operations_json, summary, model, prompt_version, prompt_tokens, completion_tokens, cached_tokens, created_at)
                    VALUES(100, 7, 100, 200, 'old-hash', '[]', '旧摘要', 'test', 'v1', 10, 2, 0, 123)"""
            )
            statement.executeUpdate(
                """CREATE TABLE profile_group_cursor(
                    bot_id INTEGER NOT NULL, group_id INTEGER NOT NULL, cursor_time INTEGER NOT NULL,
                    snapshot_end_time INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(bot_id, group_id)
                )"""
            )
            statement.executeUpdate("INSERT INTO profile_group_cursor VALUES(1, 300, 200, 1000, 123)")
            if (version != null) {
                statement.executeUpdate("CREATE TABLE user_profile_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                statement.executeUpdate("INSERT INTO user_profile_meta VALUES('schema_version', '$version')")
            }
        }
    }

    private fun connect(path: String): Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    private fun count(connection: Connection, table: String): Int = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM $table").use { results ->
            assertTrue(results.next())
            results.getInt(1)
        }
    }
}
