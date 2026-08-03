package top.jie65535.mirai.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactSnapshotStoreTest {
    @Test
    fun storesAndLoadsContactProfileHintsFromHistoryDatabase() {
        val directory = Files.createTempDirectory("jchatgpt-contact-snapshot-test-")
        try {
            ContactSnapshotStore.init(directory.toFile())
            ContactSnapshotStore.save(
                ContactSnapshotBatch(
                    botId = 1,
                    capturedAt = 1_000,
                    users = listOf(
                        ContactUserSnapshot(
                            botId = 1,
                            userId = 100,
                            nickname = "昵称",
                            remark = "好友备注",
                            sex = "female",
                            age = 18,
                            qLevel = 80,
                            sign = "公开签名",
                            updatedAt = 1_000,
                        )
                    ),
                    friends = listOf(100),
                    groups = listOf(
                        ContactGroupSnapshot(
                            botId = 1,
                            groupId = 300,
                            name = "测试群",
                            memberCount = 2,
                            maxMemberCount = 500,
                            updatedAt = 1_000,
                        )
                    ),
                    members = listOf(
                        ContactGroupMemberSnapshot(
                            botId = 1,
                            groupId = 300,
                            userId = 100,
                            nickname = "群内昵称",
                            nameCard = "群名片",
                            role = "admin",
                            specialTitle = "头衔",
                            area = "上海",
                            level = 12,
                            qLevel = 80,
                            joinTime = 900,
                            lastSpeakTime = 990,
                            updatedAt = 1_000,
                        )
                    ),
                    completeFriendList = true,
                    completeGroupList = true,
                    completeMemberGroupIds = setOf(300),
                )
            )

            val database = directory.resolve("chat-history.sqlite").toFile()
            val hint = ContactSnapshotStore.loadProfileHints(
                databaseFile = database,
                botId = 1,
                groupIds = listOf(300),
                userIds = listOf(100),
            ).getValue(100)

            assertEquals("昵称", hint.nickname)
            assertEquals("好友备注", hint.remark)
            assertTrue(hint.isFriend)
            assertEquals("群名片", hint.displayName)
            assertEquals("测试群", hint.memberships.single().groupName)
            assertEquals("admin", hint.memberships.single().role)
            assertEquals(
                100L,
                ContactSnapshotStore.findUsersByName(1, 300, "群名").single().userId,
            )

            ContactSnapshotStore.save(
                ContactSnapshotBatch(
                    botId = 1,
                    capturedAt = 2_000,
                    completeFriendList = true,
                    completeGroupList = true,
                )
            )
            val staleRelationsRemoved = ContactSnapshotStore.loadProfileHints(
                databaseFile = database,
                botId = 1,
                groupIds = listOf(300),
                userIds = listOf(100),
            ).getValue(100)
            assertFalse(staleRelationsRemoved.isFriend)
            assertTrue(staleRelationsRemoved.memberships.isEmpty())
            assertTrue(ContactSnapshotStore.findUsersByName(1, 300, "群名").isEmpty())
        } finally {
            ContactSnapshotStore.close()
            directory.toFile().deleteRecursively()
        }
    }
}
