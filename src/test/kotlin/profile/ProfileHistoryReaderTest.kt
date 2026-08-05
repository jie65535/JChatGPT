package top.jie65535.mirai.profile

import net.mamoe.mirai.message.data.MessageSourceKind
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfileHistoryReaderTest {
    @Test
    fun filtersPendingGroupRangesByValidMessageCount() {
        val directory = Files.createTempDirectory("jchatgpt-profile-pending-groups-test-")
        val database = directory.resolve("history.sqlite")
        try {
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
                }
                connection.prepareStatement(
                    "INSERT INTO message_record(" +
                            "bot_id, from_id, target_id, time, kind, code, recalled" +
                            ") VALUES (?, 100, ?, ?, ?, '[]', ?)"
                ).use { statement ->
                    fun insert(
                        botId: Long,
                        groupId: Long,
                        time: Int,
                        kind: MessageSourceKind = MessageSourceKind.GROUP,
                        recalled: Int = 0,
                    ) {
                        statement.setLong(1, botId)
                        statement.setLong(2, groupId)
                        statement.setInt(3, time)
                        statement.setInt(4, kind.ordinal)
                        statement.setInt(5, recalled)
                        statement.executeUpdate()
                    }

                    insert(1, 10, 100)
                    insert(1, 10, 110, recalled = 1)
                    insert(1, 10, 120)
                    insert(1, 10, 130)
                    insert(1, 20, 100)
                    insert(1, 20, 110, kind = MessageSourceKind.FRIEND)
                    insert(2, 30, 100)
                    insert(2, 30, 110)
                    insert(1, 40, 100)
                    insert(1, 40, 1_000)
                }
            }

            val reader = ProfileHistoryReader(database.toFile())
            val ranges = listOf(
                ProfileHistoryReader.GroupTimeBounds(1, 10, 110, 131),
                ProfileHistoryReader.GroupTimeBounds(1, 20, 90, 120),
                ProfileHistoryReader.GroupTimeBounds(1, 30, 90, 120),
            )

            assertEquals(
                listOf(10L),
                reader.filterGroupRangesByMinimumMessageCount(ranges, 2).map { it.groupId },
            )
            assertEquals(
                listOf(10L, 20L),
                reader.filterGroupRangesByMinimumMessageCount(ranges, 1).map { it.groupId },
            )
            assertEquals(
                listOf(10L, 30L),
                reader.filterGroupRangesByOldestPendingMessageTime(
                    ranges = ranges + ProfileHistoryReader.GroupTimeBounds(1, 40, 90, 1_001),
                    oldestAllowedTime = 115,
                ).map { it.groupId },
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun keepsEqualTimestampTargetMessagesTogetherAndLoadsGroupContext() {
        val directory = Files.createTempDirectory("jchatgpt-profile-history-test-")
        val database = directory.resolve("history.sqlite")
        try {
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
                }
                connection.prepareStatement(
                    "INSERT INTO message_record(" +
                            "bot_id, from_id, target_id, time, kind, code, recalled" +
                            ") VALUES (1, ?, ?, ?, ?, ?, ?)"
                ).use { statement ->
                    fun insert(
                        fromId: Long,
                        groupId: Long,
                        time: Int,
                        text: String,
                        kind: MessageSourceKind = MessageSourceKind.GROUP,
                        recalled: Int = 0,
                    ) {
                        statement.setLong(1, fromId)
                        statement.setLong(2, groupId)
                        statement.setInt(3, time)
                        statement.setInt(4, kind.ordinal)
                        statement.setString(5, """[{"type":"PlainText","content":"$text"}]""")
                        statement.setInt(6, recalled)
                        statement.executeUpdate()
                    }
                    insert(TARGET, 10, 100, "目标发言一")
                    insert(OTHER, 10, 110, "用于理解语境的回复")
                    insert(OTHER, 10, 110, "同一秒的补充回复")
                    insert(TARGET, 10, 130, "目标发言二")
                    insert(TARGET, 20, 130, "同一秒的另一群发言")
                    insert(OTHER, 20, 140, "后续上下文")
                    insert(TARGET, 10, 200, "下一批目标发言")
                    insert(TARGET, 30, 300, "私聊消息", kind = MessageSourceKind.FRIEND)
                    insert(TARGET, 40, 400, "已撤回群消息", recalled = 1)
                }
            }

            val reader = ProfileHistoryReader(database.toFile())
            assertEquals(listOf(10L, 20L), reader.listGroupTimeBounds().map { it.groupId })
            val bounds = assertNotNull(reader.findUserTimeBounds(TARGET))
            assertEquals(100, bounds.startTime)
            assertEquals(201, bounds.endTime)

            val first = assertNotNull(
                reader.loadNextBatch(
                    userId = TARGET,
                    startTime = bounds.startTime,
                    snapshotEndTime = bounds.endTime,
                    targetMessageLimit = 2,
                    maxEpisodes = 1,
                    episodeGapSeconds = 60,
                    contextBeforeMessages = 2,
                    contextAfterMessages = 2,
                    contextCoreMessages = 20,
                    maxMessageChars = 200,
                )
            )
            assertEquals(131, first.endTime)
            assertEquals(3, first.evidenceByRef.values.count { it.record.fromId == TARGET })
            assertTrue(first.messages.any { it.record.fromId == OTHER })
            assertEquals("TARGET", first.aliases[TARGET])

            val second = assertNotNull(
                reader.loadNextBatch(
                    userId = TARGET,
                    startTime = first.endTime,
                    snapshotEndTime = bounds.endTime,
                    targetMessageLimit = 2,
                    maxEpisodes = 10,
                    episodeGapSeconds = 60,
                    contextBeforeMessages = 2,
                    contextAfterMessages = 2,
                    contextCoreMessages = 20,
                    maxMessageChars = 200,
                )
            )
            assertEquals(201, second.endTime)
            assertEquals(1, second.evidenceByRef.values.count { it.record.fromId == TARGET })

            val conversation = assertNotNull(
                reader.loadConversationBatch(
                    botId = 1,
                    groupId = 10,
                    startTime = 90,
                    endTime = 150,
                    messageLimit = 10,
                    maxMessageChars = 200,
                )
            )
            assertTrue(conversation.authoredTextCharsByUser.getValue(TARGET) > 0)
            assertTrue(conversation.messages.all { it.record.targetId == 10L })
            assertTrue(conversation.messages.all { it.record.time in 90 until 150 })

            val groupBounds = assertNotNull(reader.findGroupTimeBounds(10))
            assertEquals(1, groupBounds.botId)
            assertEquals(10L, groupBounds.groupId)
            assertEquals(100, groupBounds.startTime)
            assertEquals(201, groupBounds.endTime)
            val firstGroupBatch = assertNotNull(
                reader.loadNextConversationBatch(
                    botId = groupBounds.botId,
                    groupId = 10,
                    startTime = groupBounds.startTime,
                    snapshotEndTime = groupBounds.endTime,
                    messageLimit = 2,
                    maxMessageChars = 200,
                    idleGapSeconds = 0,
                )
            )
            assertEquals(111, firstGroupBatch.endTime)
            assertEquals(listOf(100, 110, 110), firstGroupBatch.messages.map { it.record.time })
            val secondGroupBatch = assertNotNull(
                reader.loadNextConversationBatch(
                    botId = groupBounds.botId,
                    groupId = 10,
                    startTime = firstGroupBatch.endTime,
                    snapshotEndTime = groupBounds.endTime,
                    messageLimit = 2,
                    maxMessageChars = 200,
                    idleGapSeconds = 0,
                )
            )
            assertEquals(201, secondGroupBatch.endTime)
            assertEquals(listOf(130, 200), secondGroupBatch.messages.map { it.record.time })

            val defaultAdaptiveGroupBatch = assertNotNull(
                reader.loadNextConversationBatch(
                    botId = groupBounds.botId,
                    groupId = 10,
                    startTime = groupBounds.startTime,
                    snapshotEndTime = groupBounds.endTime,
                    messageLimit = 2,
                    maxMessageChars = 200,
                )
            )
            assertEquals(201, defaultAdaptiveGroupBatch.endTime)
            assertEquals(listOf(100, 110, 110, 130, 200), defaultAdaptiveGroupBatch.messages.map { it.record.time })
            assertEquals(listOf(1, 1, 1, 1, 1), defaultAdaptiveGroupBatch.messages.map { it.episodeIndex })

            val adaptiveGroupBatch = assertNotNull(
                reader.loadNextConversationBatch(
                    botId = groupBounds.botId,
                    groupId = 10,
                    startTime = groupBounds.startTime,
                    snapshotEndTime = groupBounds.endTime,
                    messageLimit = 2,
                    maxMessageChars = 200,
                    idleGapSeconds = 30,
                    targetContentChars = 10_000,
                    maxMessages = 20,
                    maxContentChars = 100_000,
                    maxPackedSpanSeconds = 1_000,
                )
            )
            assertEquals(131, adaptiveGroupBatch.endTime)
            assertEquals(listOf(100, 110, 110, 130), adaptiveGroupBatch.messages.map { it.record.time })
            assertEquals(listOf(1, 1, 1, 1), adaptiveGroupBatch.messages.map { it.episodeIndex })

            val packedSmallEpisodes = assertNotNull(
                reader.loadNextConversationBatch(
                    botId = groupBounds.botId,
                    groupId = 10,
                    startTime = groupBounds.startTime,
                    snapshotEndTime = groupBounds.endTime,
                    messageLimit = 10,
                    maxMessageChars = 200,
                    idleGapSeconds = 30,
                    targetContentChars = 10_000,
                    maxMessages = 20,
                    maxContentChars = 100_000,
                    maxPackedSpanSeconds = 1_000,
                )
            )
            assertEquals(201, packedSmallEpisodes.endTime)
            assertEquals(listOf(1, 1, 1, 1, 2), packedSmallEpisodes.messages.map { it.episodeIndex })

            val hardLimitedConversation = assertNotNull(
                reader.loadNextConversationBatch(
                    botId = groupBounds.botId,
                    groupId = 10,
                    startTime = groupBounds.startTime,
                    snapshotEndTime = groupBounds.endTime,
                    messageLimit = 2,
                    maxMessageChars = 200,
                    idleGapSeconds = 30,
                    targetContentChars = 10_000,
                    maxMessages = 3,
                    maxContentChars = 100_000,
                    maxPackedSpanSeconds = 1_000,
                )
            )
            assertEquals(111, hardLimitedConversation.endTime)
            assertEquals(listOf(100, 110, 110), hardLimitedConversation.messages.map { it.record.time })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun loadsLatestContinuousConversationAcrossFormerMessageLimit() {
        val directory = Files.createTempDirectory("jchatgpt-profile-latest-conversation-test-")
        val database = directory.resolve("history.sqlite")
        try {
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
                }
                connection.prepareStatement(
                    "INSERT INTO message_record(" +
                            "bot_id, from_id, target_id, time, kind, code, recalled" +
                            ") VALUES (1, ?, 50, ?, ?, ?, 0)"
                ).use { statement ->
                    fun insert(time: Int, text: String) {
                        statement.setLong(1, TARGET)
                        statement.setInt(2, time)
                        statement.setInt(3, MessageSourceKind.GROUP.ordinal)
                        statement.setString(4, """[{"type":"PlainText","content":"$text"}]""")
                        statement.executeUpdate()
                    }

                    insert(100, "空窗前的旧消息")
                    repeat(160) { index -> insert(2_000 + index, "连续会话消息 $index") }
                    repeat(4) { index -> insert(2_159, "同秒高频消息 $index") }
                }
            }

            val reader = ProfileHistoryReader(database.toFile())
            val conversation = assertNotNull(
                reader.loadLatestConversationBatch(
                    botId = 1,
                    groupId = 50,
                    startTime = 0,
                    endTime = 3_000,
                    maxMessageChars = 200,
                    idleGapSeconds = 30,
                    maxMessages = 800,
                    maxContentChars = 100_000,
                    maxSpanSeconds = 5_000,
                )
            )
            assertEquals(164, conversation.messages.size)
            assertEquals(2_000, conversation.startTime)
            assertEquals(2_000, conversation.messages.first().record.time)
            assertEquals(2_159, conversation.messages.last().record.time)
            assertTrue(conversation.messages.all { it.episodeIndex == 1 })

            val hardLimited = assertNotNull(
                reader.loadLatestConversationBatch(
                    botId = 1,
                    groupId = 50,
                    startTime = 0,
                    endTime = 3_000,
                    maxMessageChars = 200,
                    idleGapSeconds = 30,
                    maxMessages = 3,
                    maxContentChars = 100_000,
                    maxSpanSeconds = 5_000,
                )
            )
            assertEquals(3, hardLimited.messages.size)
            assertEquals(listOf(2_159, 2_159, 2_159), hardLimited.messages.map { it.record.time })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    companion object {
        private const val TARGET = 100L
        private const val OTHER = 200L
    }
}
