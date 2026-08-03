package top.jie65535.mirai.profile

import net.mamoe.mirai.message.data.MessageSourceKind
import top.jie65535.mirai.data.ChatMessageRecord
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UserProfileStoreTest {
    @Test
    fun commitsProfileItemsRevisionAndWaterline() {
        val directory = Files.createTempDirectory("jchatgpt-profile-test-")
        try {
            UserProfileStore.init(directory.toFile())
            val batch = ProfileHistoryBatch(
                userId = 100,
                startTime = 100,
                endTime = 200,
                messages = listOf(
                    ProfilePromptMessage(
                        record = ChatMessageRecord(
                            botId = 1,
                            fromId = 100,
                            targetId = 300,
                            ids = null,
                            internalIds = null,
                            time = 150,
                            kind = MessageSourceKind.GROUP,
                            code = "message",
                        ),
                        text = "message",
                        evidenceRef = 1,
                        episodeIndex = 1,
                    )
                ),
                aliases = mapOf(100L to "TARGET"),
                inputHash = "input-hash",
            )
            val item = UserProfileItem(
                id = "item-1",
                category = ProfileCategory.INTEREST,
                content = "关注 Kotlin 开发",
                confidence = ProfileConfidence.MEDIUM,
                firstSeenAt = 150,
                lastConfirmedAt = 150,
            )
            val profile = UserProfileSnapshot(
                userId = 100,
                summary = "关注 Kotlin 开发。",
                version = 1,
                cursorTime = 200,
                snapshotEndTime = 1_000,
                reliable = true,
                model = "test-model",
                promptVersion = "test-prompt",
                updatedAt = 123,
                items = listOf(item),
            )
            UserProfileStore.commit(
                reduction = ProfileReduction(
                    profile = profile,
                    operations = listOf(
                        AppliedProfileOperation(
                            action = ProfileOperationAction.ADD,
                            itemId = item.id,
                            category = item.category,
                            content = item.content,
                            confidence = item.confidence,
                            relatedUserId = null,
                            evidenceRefs = listOf(1),
                        )
                    ),
                ),
                batch = batch,
                usage = ProfileTokenUsage(100, 20, 50),
                source = ProfileRevisionSource.CONVERSATION,
            )

            val loaded = assertNotNull(UserProfileStore.load(100))
            assertEquals(200, loaded.cursorTime)
            assertEquals(1_000, loaded.snapshotEndTime)
            assertEquals("关注 Kotlin 开发。", loaded.summary)
            assertEquals(item, loaded.items.single())
            assertTrue(loaded.reliable)
            assertNotNull(UserProfileStore.lastRevisionAt(100, ProfileRevisionSource.CONVERSATION))
            assertTrue(UserProfileStore.isConversationProcessed("input-hash"))
        } finally {
            UserProfileStore.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun migratesRevisionSourceColumnWithoutRebuildingExistingDatabase() {
        val directory = Files.createTempDirectory("jchatgpt-profile-migration-test-")
        val database = directory.resolve("user-profile.sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        CREATE TABLE profile_revision(
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            user_id INTEGER NOT NULL,
                            profile_version INTEGER NOT NULL,
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
                            UNIQUE(user_id, start_time, end_time, input_hash)
                        )
                        """.trimIndent()
                    )
                }
            }

            UserProfileStore.init(directory.toFile())
            UserProfileStore.close()

            DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
                val columns = connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA table_info(profile_revision)").use { results ->
                        buildSet {
                            while (results.next()) add(results.getString("name"))
                        }
                    }
                }
                assertTrue("source" in columns)
                val tables = connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { results ->
                        buildSet {
                            while (results.next()) add(results.getString("name"))
                        }
                    }
                }
                assertTrue("profile_group_cursor" in tables)
                val version = connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT value FROM user_profile_meta WHERE key = 'schema_version'"
                    ).use { results ->
                        assertTrue(results.next())
                        results.getString(1)
                    }
                }
                assertEquals("3", version)
            }
        } finally {
            UserProfileStore.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun persistsGroupAnalysisCursor() {
        val directory = Files.createTempDirectory("jchatgpt-profile-group-cursor-test-")
        try {
            UserProfileStore.init(directory.toFile())
            val cursor = GroupProfileCursor(
                botId = 1,
                groupId = 300,
                cursorTime = 200,
                snapshotEndTime = 1_000,
                updatedAt = 123,
            )

            UserProfileStore.saveGroupCursor(cursor)
            assertEquals(cursor, UserProfileStore.loadGroupCursor(1, 300))
            assertEquals(listOf(cursor), UserProfileStore.loadGroupCursors())

            val advanced = cursor.copy(cursorTime = 400, updatedAt = 456)
            UserProfileStore.saveGroupCursor(advanced)
            assertEquals(advanced, UserProfileStore.loadGroupCursor(1, 300))
            assertEquals(listOf(advanced), UserProfileStore.loadGroupCursors())
        } finally {
            UserProfileStore.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun commitsAllConversationProfilesInOneTransactionAndCountsUsageOnce() {
        val directory = Files.createTempDirectory("jchatgpt-profile-conversation-commit-test-")
        try {
            UserProfileStore.init(directory.toFile())
            val entries = listOf(100L, 200L).map { userId ->
                val batch = batchFor(userId, "shared-conversation-hash")
                val profile = UserProfileSnapshot(
                    userId = userId,
                    summary = "用户 $userId 的测试摘要",
                    version = 1,
                    cursorTime = 0,
                    snapshotEndTime = 0,
                    reliable = true,
                    model = "test-model",
                    promptVersion = "test-prompt",
                    items = listOf(itemFor("item-$userId")),
                )
                ProfileReduction(profile, emptyList()) to batch
            }

            UserProfileStore.commitConversation(entries, ProfileTokenUsage(100, 20, 50))

            assertNotNull(UserProfileStore.load(100))
            assertNotNull(UserProfileStore.load(200))
            assertEquals(listOf(100L, 200L), UserProfileStore.listUserIds())
            assertTrue(UserProfileStore.isConversationProcessed("shared-conversation-hash"))
            val database = directory.resolve("user-profile.sqlite")
            DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT COUNT(*), SUM(prompt_tokens), SUM(completion_tokens) " +
                                "FROM profile_revision WHERE input_hash = 'shared-conversation-hash'"
                    ).use { results ->
                        assertTrue(results.next())
                        assertEquals(2, results.getInt(1))
                        assertEquals(100, results.getInt(2))
                        assertEquals(20, results.getInt(3))
                    }
                }
            }
        } finally {
            UserProfileStore.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun commitsCompactionWithoutNewEvidenceAndReassignsExistingSupport() {
        val directory = Files.createTempDirectory("jchatgpt-profile-compaction-commit-test-")
        try {
            UserProfileStore.init(directory.toFile())
            val batch = batchFor(100, "initial-hash")
            val first = itemFor("item-1")
            val second = itemFor("item-2").copy(content = "关注 Kotlin 生态")
            val initialProfile = UserProfileSnapshot(
                userId = 100,
                summary = "关注 Kotlin。",
                version = 1,
                cursorTime = 200,
                snapshotEndTime = 1_000,
                reliable = true,
                model = "test-model",
                promptVersion = "test-prompt",
                items = listOf(first, second),
            )
            UserProfileStore.commit(
                reduction = ProfileReduction(
                    profile = initialProfile,
                    operations = listOf(
                        applied(first, ProfileOperationAction.ADD, listOf(1)),
                        applied(second, ProfileOperationAction.ADD, listOf(1)),
                    ),
                ),
                batch = batch,
                usage = ProfileTokenUsage(),
            )

            val merged = first.copy(content = "关注 Kotlin 及其生态")
            val compactedProfile = initialProfile.copy(
                summary = "关注 Kotlin 及其生态。",
                version = 2,
                items = listOf(merged),
            )
            val plan = ProfileCompactionPlan(
                reduction = ProfileReduction(
                    profile = compactedProfile,
                    operations = listOf(
                        applied(merged, ProfileOperationAction.UPDATE, emptyList()),
                        applied(second, ProfileOperationAction.DELETE, emptyList()),
                    ),
                ),
                supportReassignments = mapOf(second.id to first.id),
                mergedGroups = 1,
                rewrittenItems = 0,
                deletedItems = 0,
            )
            UserProfileStore.commitCompaction(
                plan = plan,
                batch = ProfileHistoryBatch(
                    userId = 100,
                    startTime = 150,
                    endTime = 151,
                    messages = emptyList(),
                    aliases = mapOf(100L to "TARGET"),
                    inputHash = "compact-hash",
                ),
                usage = ProfileTokenUsage(50, 10, 0),
            )

            val loaded = assertNotNull(UserProfileStore.load(100))
            assertEquals(200, loaded.cursorTime)
            assertEquals(listOf(merged), loaded.items)
            assertEquals(2, UserProfileStore.loadSupportStats(100).getValue(first.id).count)
            assertTrue(second.id !in UserProfileStore.loadSupportStats(100))
            assertNotNull(UserProfileStore.lastRevisionAt(100, ProfileRevisionSource.COMPACTION))
        } finally {
            UserProfileStore.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun batchFor(userId: Long, inputHash: String) = ProfileHistoryBatch(
        userId = userId,
        startTime = 100,
        endTime = 200,
        messages = listOf(
            ProfilePromptMessage(
                record = ChatMessageRecord(
                    botId = 1,
                    fromId = userId,
                    targetId = 300,
                    ids = null,
                    internalIds = null,
                    time = 150,
                    kind = MessageSourceKind.GROUP,
                    code = "message",
                ),
                text = "message",
                evidenceRef = 1,
                episodeIndex = 1,
            )
        ),
        aliases = mapOf(userId to "TARGET"),
        inputHash = inputHash,
    )

    private fun itemFor(id: String) = UserProfileItem(
        id = id,
        category = ProfileCategory.INTEREST,
        content = "关注 Kotlin 开发",
        confidence = ProfileConfidence.MEDIUM,
        firstSeenAt = 150,
        lastConfirmedAt = 150,
    )

    private fun applied(
        item: UserProfileItem,
        action: ProfileOperationAction,
        evidenceRefs: List<Int>,
    ) = AppliedProfileOperation(
        action = action,
        itemId = item.id,
        category = item.category,
        content = item.content,
        confidence = item.confidence,
        relatedUserId = item.relatedUserId,
        evidenceRefs = evidenceRefs,
    )
}
