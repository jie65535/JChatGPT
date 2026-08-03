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
                )
            )
            assertEquals(201, secondGroupBatch.endTime)
            assertEquals(listOf(130, 200), secondGroupBatch.messages.map { it.record.time })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    companion object {
        private const val TARGET = 100L
        private const val OTHER = 200L
    }
}
