package top.jie65535.mirai.data

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import net.mamoe.mirai.message.data.MessageSourceKind
import top.jie65535.mirai.data.ChatHistoryMatchMode.PHRASE
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
                        assertEquals("4", results.getString(1))
                    }
                    statement.executeQuery(
                        "SELECT COUNT(*) FROM sqlite_master " +
                                "WHERE type = 'table' AND name IN " +
                                "('message_record_search', 'message_record_fts', 'message_record_bigram_fts', " +
                                        "'chat_history_sender_alias')"
                    ).use { results ->
                        assertTrue(results.next())
                        assertEquals(4, results.getInt(1))
                    }
                }
            }
        } finally {
            ChatHistoryStore.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun searchesChineseTextInDatabaseAndSupportsCursorAndContext() {
        val directory = Files.createTempDirectory("jchatgpt-history-search-test-")
        try {
            ChatHistoryStore.init(directory.toFile())
            val first = insert(directory, 100, 11, "部署失败，需要回滚", senderName = "旧名张三")
            val second = insert(directory, 101, 12, "部署成功，服务已恢复")
            val third = insert(directory, 102, 11, "确认部署失败的原因")

            val subject = ChatHistorySubject(1, MessageSourceKind.GROUP, 1000)
            val firstPage = ChatHistoryStore.search(
                ChatHistorySearchRequest(
                    subject = subject,
                    query = "部署失败",
                    matchMode = PHRASE,
                    start = 0,
                    end = 200,
                    limit = 1,
                )
            )
            assertEquals(2L, firstPage.totalMatches)
            assertEquals(third, firstPage.records.single().id)
            assertNotNull(firstPage.nextCursor)

            val secondPage = ChatHistoryStore.search(
                ChatHistorySearchRequest(
                    subject = subject,
                    query = "部署失败",
                    matchMode = PHRASE,
                    start = 0,
                    end = 200,
                    limit = 1,
                    cursor = firstPage.nextCursor,
                )
            )
            assertEquals(null, secondPage.totalMatches)
            assertEquals(first, secondPage.records.single().id)
            assertFalse(secondPage.nextCursor != null)

            val context = ChatHistoryStore.findAround(subject, second, before = 1, after = 1)
            assertNotNull(context)
            assertEquals(listOf(first, second, third), context.records.map { it.id })

            val shortQuery = ChatHistoryStore.search(
                ChatHistorySearchRequest(
                    subject = subject,
                    query = "部署",
                    fromId = 11,
                    start = 0,
                    end = 200,
                    sortOrder = ChatHistorySortOrder.OLDEST,
                    limit = 10,
                )
            )
            assertEquals(2L, shortQuery.totalMatches)
            assertEquals(listOf(first, third), shortQuery.records.map { it.id })

            val mention = insert(
                directory,
                time = 103,
                fromId = 11,
                text = "",
                code = buildJsonArray {
                    add(buildJsonObject {
                        put("type", "At")
                        put("target", 2180487691L)
                    })
                }.toString(),
            )
            val literal = insert(directory, 104, 11, "筱玥检查一下")
            val mentionQuery = ChatHistoryStore.search(
                ChatHistorySearchRequest(
                    subject = subject,
                    query = "筱玥",
                    atTargetIds = setOf(2180487691L),
                    fromId = 11,
                    start = 0,
                    end = 200,
                    limit = 10,
                )
            )
            assertEquals(2L, mentionQuery.totalMatches)
            assertEquals(listOf(literal, mention), mentionQuery.records.map { it.id })

            val aliases = ChatHistoryStore.findSenderAliases(subject, "旧名")
            assertEquals(listOf(ChatHistorySenderAliasMatch(11, "旧名张三", 1)), aliases)
        } finally {
            ChatHistoryStore.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun migratesExistingSchemaAndBackfillsSearchTextWithoutReplacingMessageTable() {
        val directory = Files.createTempDirectory("jchatgpt-history-migration-test-")
        val database = directory.resolve("chat-history.sqlite")
        try {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${database.absolutePathString()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        CREATE TABLE message_record(
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
                        "CREATE TABLE chat_history_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO chat_history_meta(key, value) VALUES ('schema_version', '2')"
                    )
                    statement.executeUpdate(
                        """
                        INSERT INTO message_record(
                            bot_id, from_id, target_id, ids, internal_ids, time, kind, code, recalled
                        ) VALUES (1, 11, 1000, '1', '1', 100, ${MessageSourceKind.GROUP.ordinal},
                            '[{"type":"PlainText","content":"旧库部署失败"}]', 0)
                        """.trimIndent()
                    )
                    statement.executeUpdate(
                        """
                        INSERT INTO message_record(
                            bot_id, from_id, target_id, ids, internal_ids, time, kind, code, recalled
                        ) VALUES (1, 12, 1000, '2', '2', 101, ${MessageSourceKind.GROUP.ordinal},
                            '[{"type":"PlainText","content":"旧库部署失败后已恢复"}]', 0)
                        """.trimIndent()
                    )
                }
            }

            ChatHistoryStore.init(directory.toFile())
            assertTrue(ChatHistoryStore.isSearchIndexAvailable)
            assertFalse(ChatHistoryStore.isSearchIndexReady)
            var continueChecks = 0
            assertEquals(
                1,
                ChatHistoryStore.backfillSearchIndex(
                    batchSize = 1,
                    shouldContinue = { continueChecks++ == 0 },
                )
            )
            assertFalse(ChatHistoryStore.isSearchIndexReady)
            ChatHistoryStore.close()

            ChatHistoryStore.init(directory.toFile())
            assertFalse(ChatHistoryStore.isSearchIndexReady)
            assertEquals(1, ChatHistoryStore.backfillSearchIndex(batchSize = 1))
            assertTrue(ChatHistoryStore.isSearchIndexReady)

            val migratedSearch = ChatHistoryStore.search(
                ChatHistorySearchRequest(
                    subject = ChatHistorySubject(1, MessageSourceKind.GROUP, 1000),
                    query = "旧库部署失败",
                    matchMode = PHRASE,
                    start = 0,
                    end = 200,
                )
            )
            assertEquals(2L, migratedSearch.totalMatches)
            assertEquals(listOf(2L, 1L), migratedSearch.records.map { it.id })

            DriverManager.getConnection("jdbc:sqlite:${database.absolutePathString()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM message_record").use { results ->
                        assertTrue(results.next())
                        assertEquals(2, results.getInt(1))
                    }
                    statement.executeQuery(
                        "SELECT search_text FROM message_record_search WHERE message_id = 1"
                    ).use { results ->
                        assertTrue(results.next())
                        assertEquals("旧库部署失败", results.getString(1))
                    }
                }
            }
        } finally {
            ChatHistoryStore.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun migratesCompletedTrigramIndexAndBackfillsBigramIndex() {
        val directory = Files.createTempDirectory("jchatgpt-history-bigram-migration-test-")
        val database = directory.resolve("chat-history.sqlite")
        try {
            ChatHistoryStore.init(directory.toFile())
            val messageId = insert(directory, 100, 11, "部署完成")
            ChatHistoryStore.close()

            DriverManager.getConnection("jdbc:sqlite:${database.absolutePathString()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DROP TABLE message_record_bigram_fts")
                    statement.executeUpdate(
                        "DELETE FROM chat_history_meta WHERE key = 'search_bigram_version'"
                    )
                    statement.executeUpdate(
                        "UPDATE chat_history_meta SET value = '3' WHERE key = 'schema_version'"
                    )
                    statement.executeUpdate(
                        "UPDATE chat_history_meta SET value = '1' WHERE key = 'search_index_complete'"
                    )
                    statement.executeUpdate(
                        "UPDATE chat_history_meta SET value = '$messageId' WHERE key = 'search_backfill_cursor'"
                    )
                }
            }

            ChatHistoryStore.init(directory.toFile())
            assertFalse(ChatHistoryStore.isSearchIndexReady)
            assertEquals(1, ChatHistoryStore.backfillSearchIndex(batchSize = 1))
            assertTrue(ChatHistoryStore.isSearchIndexReady)

            val search = ChatHistoryStore.search(
                ChatHistorySearchRequest(
                    subject = ChatHistorySubject(1, MessageSourceKind.GROUP, 1000),
                    query = "部署",
                    start = 0,
                    end = 200,
                )
            )
            assertEquals(listOf(messageId), search.records.map { it.id })

            DriverManager.getConnection("jdbc:sqlite:${database.absolutePathString()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT search_text FROM message_record_search WHERE message_id = $messageId"
                    ).use { results ->
                        assertTrue(results.next())
                        assertEquals("部署完成", results.getString(1))
                    }
                    statement.executeQuery(
                        "SELECT rowid FROM message_record_bigram_fts WHERE rowid = $messageId"
                    ).use { results ->
                        assertTrue(results.next())
                    }
                }
            }
        } finally {
            ChatHistoryStore.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun insert(
        directory: java.nio.file.Path,
        time: Int,
        fromId: Long,
        text: String,
        senderName: String = "",
        code: String = buildJsonArray {
            add(buildJsonObject {
                put("type", "PlainText")
                put("content", text)
            })
        }.toString(),
    ): Long {
        val record = ChatMessageRecord(
            botId = 1,
            fromId = fromId,
            targetId = 1000,
            ids = "$time",
            internalIds = "$time",
            time = time,
            kind = MessageSourceKind.GROUP,
            code = code,
        )
        ChatHistoryStore.insertRecord(record, senderName)
        DriverManager.getConnection("jdbc:sqlite:${directory.resolve("chat-history.sqlite").absolutePathString()}").use {
            it.prepareStatement("SELECT id FROM message_record WHERE time = ? AND from_id = ?").use { query ->
                query.setInt(1, time)
                query.setLong(2, fromId)
                query.executeQuery().use { results ->
                    assertTrue(results.next())
                    return results.getLong(1)
                }
            }
        }
    }
}
