package top.jie65535.mirai.data

import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenUsageStoreTest {
    @Test
    fun recordsDetailedUsageAndScopesQueriesToCurrentConversation() {
        val directory = Files.createTempDirectory("jchatgpt-token-usage-test-")
        try {
            TokenUsageStore.init(directory.toFile())
            val now = Instant.now().epochSecond
            record(now, botId = 1, userId = 10, groupId = 100, total = 15, model = "deepseek-chat")
            record(now + 1, botId = 1, userId = 11, groupId = 100, total = 30, model = "deepseek-chat")
            record(now + 2, botId = 1, userId = 10, groupId = 200, total = 40, model = "model-b")
            record(now + 3, botId = 2, userId = 12, groupId = 100, total = 50, model = "model-c")
            record(now + 4, botId = 1, userId = 10, groupId = null, total = 60, model = "deepseek-chat")

            val startDate = LocalDate.now().minusDays(1).toString()
            val global = TokenUsageStore.summary(startDate, rankingLimit = 20)
            assertEquals(195, global.totalTokens)
            assertEquals(5, global.callCount)
            assertEquals(3, global.activeUsers)

            val currentGroup = TokenUsageStore.summary(
                startDate = startDate,
                botId = 1,
                groupId = 100,
                rankingLimit = 20,
            )
            assertEquals(45, currentGroup.totalTokens)
            assertEquals(2, currentGroup.callCount)
            assertEquals(45, currentGroup.todayTotal)
            assertEquals(45, currentGroup.usageDaily.sumOf { it.totalUnits })
            assertEquals(listOf(11L, 10L), currentGroup.topUsers.map(TokenUsageRanking::id))

            val oneGroupUser = TokenUsageStore.summary(
                startDate = startDate,
                botId = 1,
                userId = 11,
                groupId = 100,
            )
            assertEquals(30, oneGroupUser.totalTokens)
            assertEquals(1, oneGroupUser.callCount)

            val currentPrivateChat = TokenUsageStore.summary(
                startDate = startDate,
                botId = 1,
                userId = 10,
                privateOnly = true,
            )
            assertEquals(60, currentPrivateChat.totalTokens)
            assertEquals(1, currentPrivateChat.callCount)
            assertTrue(currentPrivateChat.topGroups.isEmpty())

            val details = TokenUsageStore.recent(
                startDate = startDate,
                botId = 1,
                groupId = 100,
            )
            assertEquals(listOf(11L, 10L), details.map(TokenUsageRecord::userId))
            assertTrue(details.all(TokenUsageRecord::detailed))
            assertEquals(setOf("deepseek"), details.mapNotNull(TokenUsageRecord::provider).toSet())
        } finally {
            TokenUsageStore.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun importsLegacyJsonIdempotentlyAndKeepsSourceFile() {
        val directory = Files.createTempDirectory("jchatgpt-token-legacy-test-")
        val legacy = directory.resolve("token_usage.json")
        legacy.toFile().writeText(
            """
            [
              {
                "date": "2026-08-01",
                "userId": 123,
                "userNickname": "legacy-user",
                "groupId": 456,
                "groupName": "legacy-group",
                "promptTokens": 100,
                "completionTokens": 20,
                "totalTokens": 120,
                "cachedTokens": 40,
                "callCount": 3
              }
            ]
            """.trimIndent()
        )
        val warnings = mutableListOf<String>()
        val database = directory.resolve("chat-history.sqlite")
        try {
            TokenUsageStore.init(directory.toFile()) { message, _ -> warnings += message }
            assertLegacyImport()
            TokenUsageStore.close()

            DriverManager.getConnection("jdbc:sqlite:${database.absolutePathString()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM token_usage_meta WHERE key = 'legacy_json_sha256'")
                }
            }

            TokenUsageStore.init(directory.toFile()) { message, _ -> warnings += message }
            assertLegacyImport()
            assertTrue(Files.isRegularFile(legacy))
            assertEquals(1, warnings.count { it.startsWith("已将 1 条旧 Token 聚合记录迁移") })

            DriverManager.getConnection("jdbc:sqlite:${database.absolutePathString()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA journal_mode").use { results ->
                        assertTrue(results.next())
                        assertEquals("wal", results.getString(1).lowercase())
                    }
                    statement.executeQuery("SELECT value FROM token_usage_meta WHERE key = 'schema_version'").use { results ->
                        assertTrue(results.next())
                        assertEquals("2", results.getString(1))
                    }
                    statement.executeQuery(
                        "SELECT value FROM token_usage_meta WHERE key = 'legacy_json_sha256'"
                    ).use { results ->
                        assertTrue(results.next())
                        assertEquals(64, results.getString(1).length)
                    }
                    statement.executeQuery("PRAGMA table_info(token_usage_record)").use { results ->
                        val columns = buildSet {
                            while (results.next()) add(results.getString("name"))
                        }
                        assertEquals(
                            setOf(
                                "id",
                                "occurred_at",
                                "usage_date",
                                "bot_id",
                                "user_id",
                                "user_nickname",
                                "group_id",
                                "group_name",
                                "endpoint_label",
                                "model_alias",
                                "provider",
                                "model",
                                "usage_kind",
                                "unit",
                                "input_units",
                                "output_units",
                                "total_units",
                                "prompt_tokens",
                                "completion_tokens",
                                "total_tokens",
                                "cached_tokens",
                                "call_count",
                                "detailed",
                                "legacy_key",
                            ),
                            columns,
                        )
                    }
                    statement.executeQuery("SELECT COUNT(*) FROM token_usage_record").use { results ->
                        assertTrue(results.next())
                        assertEquals(1, results.getInt(1))
                    }
                    statement.executeQuery("PRAGMA index_list(token_usage_record)").use { results ->
                        val indexes = buildSet {
                            while (results.next()) add(results.getString("name"))
                        }
                        assertTrue("idx_token_usage_kind_date" in indexes)
                    }
                }
            }
        } finally {
            TokenUsageStore.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun keepsNonTokenUnitsOutOfTokenTotalsAndIncludesThemInBreakdown() {
        val directory = Files.createTempDirectory("jchatgpt-model-usage-test-")
        try {
            TokenUsageStore.init(directory.toFile())
            val now = Instant.now().epochSecond
            record(now, botId = 1, userId = 10, groupId = 100, total = 100, model = "chat-model")
            TokenUsageStore.recordUsage(
                ModelUsageEvent(
                    timestamp = now + 1,
                    botId = 1,
                    userId = 10,
                    userNickname = "user-10",
                    groupId = 100,
                    groupName = "group-100",
                    endpointLabel = "image",
                    modelAlias = "image-main",
                    provider = "dashscope",
                    model = "qwen-image",
                    usageKind = "image",
                    unit = "images",
                    outputUnits = 1,
                    totalUnits = 1,
                    promptTokens = 0,
                    completionTokens = 0,
                    totalTokens = 0,
                )
            )
            TokenUsageStore.recordUsage(
                ModelUsageEvent(
                    timestamp = now + 2,
                    botId = 1,
                    userId = 10,
                    userNickname = "user-10",
                    groupId = 100,
                    groupName = "group-100",
                    endpointLabel = "tts",
                    modelAlias = "tts-main",
                    provider = "dashscope",
                    model = "qwen-tts",
                    usageKind = "tts",
                    unit = "characters",
                    inputUnits = 50,
                    totalUnits = 50,
                    promptTokens = 0,
                    completionTokens = 0,
                    totalTokens = 0,
                )
            )

            val summary = TokenUsageStore.summary(
                startDate = LocalDate.now().minusDays(1).toString(),
                botId = 1,
                groupId = 100,
                rankingLimit = 20,
            )
            assertEquals(100, summary.totalTokens)
            assertEquals(1, summary.callCount)
            assertEquals(3, summary.allCallCount)
            assertEquals(setOf("tokens", "images", "characters"), summary.breakdown.map { it.unit }.toSet())
            assertEquals(1, summary.breakdown.single { it.unit == "images" }.outputUnits)
            assertEquals(50, summary.breakdown.single { it.unit == "characters" }.inputUnits)
            assertEquals(1, summary.userUsage.single { it.usageKind == "image" }.totalUnits)
            assertEquals(50, summary.userUsage.single { it.usageKind == "tts" }.totalUnits)

            val imageOnly = TokenUsageStore.summary(
                startDate = LocalDate.now().minusDays(1).toString(),
                botId = 1,
                groupId = 100,
                usageKind = "image",
            )
            assertEquals(0, imageOnly.totalTokens)
            assertEquals(0, imageOnly.callCount)
            assertEquals(1, imageOnly.allCallCount)
            assertEquals(1, imageOnly.allActiveUsers)
            assertEquals("images", imageOnly.breakdown.single().unit)
            assertEquals(10, imageOnly.userUsage.single().userId)
            assertEquals(1, imageOnly.usageDaily.single().totalUnits)

            val details = TokenUsageStore.recent(botId = 1, groupId = 100)
            assertEquals(listOf("tts", "image", "chat"), details.map { it.usageKind })
            assertEquals("tts-main", details.first().modelAlias)
        } finally {
            TokenUsageStore.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun migratesVersionOneTableAndBackfillsGenericTokenUnits() {
        val directory = Files.createTempDirectory("jchatgpt-token-v1-migration-test-")
        val database = directory.resolve("chat-history.sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${database.absolutePathString()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        CREATE TABLE token_usage_record(
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            occurred_at INTEGER NOT NULL,
                            usage_date TEXT NOT NULL,
                            bot_id INTEGER,
                            user_id INTEGER NOT NULL,
                            user_nickname TEXT NOT NULL DEFAULT '',
                            group_id INTEGER,
                            group_name TEXT,
                            endpoint_label TEXT,
                            provider TEXT,
                            model TEXT,
                            prompt_tokens INTEGER NOT NULL DEFAULT 0,
                            completion_tokens INTEGER NOT NULL DEFAULT 0,
                            total_tokens INTEGER NOT NULL DEFAULT 0,
                            cached_tokens INTEGER NOT NULL DEFAULT 0,
                            call_count INTEGER NOT NULL DEFAULT 1,
                            detailed INTEGER NOT NULL DEFAULT 1,
                            legacy_key TEXT UNIQUE
                        )
                        """.trimIndent()
                    )
                    statement.executeUpdate(
                        "CREATE TABLE token_usage_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO token_usage_meta(key, value) VALUES ('schema_version', '1')"
                    )
                    statement.executeUpdate(
                        """
                        INSERT INTO token_usage_record(
                            occurred_at, usage_date, bot_id, user_id, user_nickname,
                            group_id, group_name, endpoint_label, provider, model,
                            prompt_tokens, completion_tokens, total_tokens, cached_tokens
                        ) VALUES (1, '2026-08-01', 1, 10, 'user', 100, 'group',
                                  'primary', 'deepseek', 'deepseek-chat', 80, 20, 100, 40)
                        """.trimIndent()
                    )
                }
            }

            TokenUsageStore.init(directory.toFile())
            val record = TokenUsageStore.recent(
                startDate = "2026-08-01",
                endDate = "2026-08-01",
            ).single()
            assertEquals("chat", record.usageKind)
            assertEquals("tokens", record.unit)
            assertEquals(80, record.inputUnits)
            assertEquals(20, record.outputUnits)
            assertEquals(100, record.totalUnits)
            assertEquals(100, TokenUsageStore.summary("2026-08-01", "2026-08-01").totalTokens)

            TokenUsageStore.close()
            DriverManager.getConnection("jdbc:sqlite:${database.absolutePathString()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT value FROM token_usage_meta WHERE key = 'schema_version'").use { results ->
                        assertTrue(results.next())
                        assertEquals("2", results.getString(1))
                    }
                }
            }
        } finally {
            TokenUsageStore.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun assertLegacyImport() {
        val summary = TokenUsageStore.summary("2026-08-01", "2026-08-01")
        assertEquals(100, summary.promptTokens)
        assertEquals(20, summary.completionTokens)
        assertEquals(120, summary.totalTokens)
        assertEquals(40, summary.cachedTokens)
        assertEquals(3, summary.callCount)
        assertEquals(
            0,
            TokenUsageStore.summary(
                startDate = "2026-08-01",
                endDate = "2026-08-01",
                botId = 1,
                groupId = 456,
            ).totalTokens,
        )

        val record = TokenUsageStore.recent(
            limit = 10,
            startDate = "2026-08-01",
            endDate = "2026-08-01",
        ).single()
        assertFalse(record.detailed)
        assertEquals(3, record.callCount)
        assertEquals(100, record.inputUnits)
        assertEquals(20, record.outputUnits)
        assertEquals(120, record.totalUnits)
    }

    private fun record(
        timestamp: Long,
        botId: Long,
        userId: Long,
        groupId: Long?,
        total: Int,
        model: String,
    ) {
        TokenUsageStore.record(
            timestamp = timestamp,
            botId = botId,
            userId = userId,
            userNickname = "user-$userId",
            groupId = groupId,
            groupName = groupId?.let { "group-$it" },
            endpointLabel = "primary",
            apiBaseUrl = "https://api.deepseek.com/v1/",
            model = model,
            promptTokens = total - 5,
            completionTokens = 5,
            totalTokens = total,
            cachedTokens = 2,
        )
    }
}
