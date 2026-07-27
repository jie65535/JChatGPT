package top.jie65535.mirai

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatHistoryStoreTest {
    @Test
    fun initializesSQLiteSchemaAndWalMode() {
        val directory = Files.createTempDirectory("jchatgpt-history-test-")
        try {
            ChatHistoryStore.init(directory.toFile())
            assertTrue(ChatHistoryStore.isAvailable)

            val database = directory.resolve("chat-history.sqlite")
            assertTrue(Files.isRegularFile(database))

            DriverManager.getConnection("jdbc:sqlite:${database.absolutePathString()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA journal_mode").use { results ->
                        assertTrue(results.next())
                        assertEquals("wal", results.getString(1).lowercase())
                    }
                    statement.executeQuery(
                        "SELECT COUNT(*) FROM sqlite_master " +
                                "WHERE type = 'table' AND name IN ('message_record', 'chat_history_meta')"
                    ).use { results ->
                        assertTrue(results.next())
                        assertEquals(2, results.getInt(1))
                    }
                    statement.executeQuery("PRAGMA table_info(message_record)").use { results ->
                        val columns = buildSet {
                            while (results.next()) add(results.getString("name"))
                        }
                        assertEquals(
                            setOf(
                                "id",
                                "bot_id",
                                "from_id",
                                "target_id",
                                "ids",
                                "internal_ids",
                                "time",
                                "kind",
                                "code",
                                "recalled",
                            ),
                            columns,
                        )
                    }
                    statement.executeQuery(
                        "SELECT value FROM chat_history_meta WHERE key = 'schema_version'"
                    ).use { results ->
                        assertTrue(results.next())
                        assertEquals("2", results.getString(1))
                    }
                }
            }
        } finally {
            ChatHistoryStore.close()
            directory.toFile().deleteRecursively()
        }
    }
}
