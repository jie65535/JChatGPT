package top.jie65535.mirai.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import net.mamoe.mirai.Bot
import net.mamoe.mirai.contact.Friend
import net.mamoe.mirai.contact.Member
import net.mamoe.mirai.contact.NormalMember
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.config.PluginConfig
import top.mrxiaom.overflow.contact.RemoteBot
import top.mrxiaom.overflow.contact.RemoteGroup
import top.mrxiaom.overflow.contact.RemoteUser
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object ContactSnapshotRefresher {
    private const val ACTION_TIMEOUT_MS = 60_000L
    private const val GET_FRIEND_LIST = "get_friend_list"
    private const val GET_GROUP_LIST = "get_group_list"
    private const val GET_GROUP_MEMBER_LIST = "get_group_member_list"

    private val json = Json { ignoreUnknownKeys = true }
    private val jobs = ConcurrentHashMap<Long, Job>()

    fun scheduleAll(reason: String) {
        Bot.instances.forEach { bot -> schedule(bot, reason) }
    }

    fun schedule(bot: Bot, reason: String) {
        if (!PluginConfig.contactSnapshotEnabled || !ContactSnapshotStore.isAvailable) return
        synchronized(jobs) {
            if (jobs.containsKey(bot.id)) return
            jobs[bot.id] = JChatGPT.launch(Dispatchers.IO) {
                delay(PluginConfig.contactSnapshotInitialDelaySeconds.coerceAtLeast(0).seconds)
                while (isActive) {
                    refresh(bot, reason)
                    val minutes = PluginConfig.contactSnapshotRefreshIntervalMinutes
                    if (minutes <= 0) break
                    delay(minutes.minutes)
                }
            }
        }
    }

    fun clear() {
        synchronized(jobs) {
            jobs.values.forEach(Job::cancel)
            jobs.clear()
        }
    }

    suspend fun refresh(bot: Bot, reason: String = "manual"): ContactSnapshotBatch? = withContext(Dispatchers.IO) {
        if (!PluginConfig.contactSnapshotEnabled || !ContactSnapshotStore.isAvailable) return@withContext null
        try {
            val batch = (bot as? RemoteBot)?.let { remoteBot ->
                pullFromOneBot(bot, remoteBot)
            } ?: pullFromMirai(bot)
            ContactSnapshotStore.save(batch)
            JChatGPT.logger.info(
                "CONTACT_SNAPSHOT bot=${bot.id} reason=$reason friends=${batch.friends.size} " +
                        "groups=${batch.groups.size} members=${batch.members.size} users=${batch.users.size}"
            )
            batch
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            JChatGPT.logger.warning("联系人快照刷新失败: bot=${bot.id}, reason=$reason", cause)
            null
        }
    }

    private suspend fun pullFromOneBot(
        bot: Bot,
        remoteBot: RemoteBot,
    ): ContactSnapshotBatch {
        val capturedAt = System.currentTimeMillis()
        val users = linkedMapOf<Long, ContactUserSnapshot>()
        val friendIds = mutableListOf<Long>()
        val groups = mutableListOf<ContactGroupSnapshot>()
        val members = mutableListOf<ContactGroupMemberSnapshot>()
        val completeMemberGroupIds = mutableSetOf<Long>()

        val cachedFriends = bot.friends.associateBy { friend -> friend.id }
        executeList(remoteBot, GET_FRIEND_LIST).forEach { data ->
            val userId = data.long("user_id")?.takeIf { it > 0 } ?: return@forEach
            val friend = cachedFriends[userId]
            friendIds += userId
            users.merge(data.toFriendSnapshot(bot.id, userId, capturedAt, friend))
        }

        val cachedGroups = bot.groups.associateBy { it.id }
        executeList(remoteBot, GET_GROUP_LIST).forEach { groupData ->
            val groupId = groupData.long("group_id")?.takeIf { it > 0 } ?: return@forEach
            val memberRows = try {
                executeList(
                    remoteBot,
                    GET_GROUP_MEMBER_LIST,
                    buildJsonObject {
                        put("group_id", groupId)
                        put("no_cache", false)
                    }.toString(),
                ).also { completeMemberGroupIds += groupId }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                JChatGPT.logger.warning("拉取群 $groupId 成员列表失败，保留上一版成员快照", cause)
                null
            }

            groups += ContactGroupSnapshot(
                botId = bot.id,
                groupId = groupId,
                name = groupData.text("group_name").ifBlank { cachedGroups[groupId]?.name.orEmpty() },
                memberCount = groupData.int("member_count") ?: memberRows?.size ?: 0,
                maxMemberCount = groupData.int("max_member_count") ?: 0,
                updatedAt = capturedAt,
            )

            memberRows.orEmpty().forEach { memberData ->
                val userId = memberData.long("user_id")?.takeIf { it > 0 } ?: return@forEach
                val member = cachedGroups[groupId]?.get(userId)
                members += memberData.toMemberSnapshot(bot.id, groupId, userId, capturedAt, member)
                users.merge(memberData.toMemberUserSnapshot(bot.id, userId, capturedAt, member))
            }

            delayBetweenGroups()
        }

        return ContactSnapshotBatch(
            botId = bot.id,
            capturedAt = capturedAt,
            users = users.values.toList(),
            friends = friendIds,
            groups = groups,
            members = members,
            completeFriendList = true,
            completeGroupList = true,
            completeMemberGroupIds = completeMemberGroupIds,
        )
    }

    private suspend fun pullFromMirai(bot: Bot): ContactSnapshotBatch {
        val capturedAt = System.currentTimeMillis()
        val users = linkedMapOf<Long, ContactUserSnapshot>()
        val friendIds = mutableListOf<Long>()
        val groups = mutableListOf<ContactGroupSnapshot>()
        val members = mutableListOf<ContactGroupMemberSnapshot>()
        val completeMemberGroupIds = mutableSetOf<Long>()

        bot.friends.toList().forEach { friend ->
            friendIds += friend.id
            users.merge(friend.toUserSnapshot(bot.id, capturedAt))
        }

        bot.groups.toList().forEach { group ->
            val groupData = group.onebotJson()
            val groupMembers = try {
                ((group as? RemoteGroup)?.updateGroupMemberList()?.toList() ?: group.members.toList())
                    .also { completeMemberGroupIds += group.id }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                JChatGPT.logger.warning("刷新群 ${group.id} 成员列表失败，保留上一版成员快照", cause)
                emptyList()
            }

            groups += ContactGroupSnapshot(
                botId = bot.id,
                groupId = group.id,
                name = group.name,
                memberCount = groupData.int("member_count") ?: groupMembers.size,
                maxMemberCount = groupData.int("max_member_count") ?: 0,
                updatedAt = capturedAt,
            )
            groupMembers.forEach { member ->
                val memberData = member.onebotJson()
                members += member.toMemberSnapshot(bot.id, capturedAt, memberData)
                users.merge(member.toUserSnapshot(bot.id, capturedAt, memberData))
            }
            delayBetweenGroups()
        }

        return ContactSnapshotBatch(
            botId = bot.id,
            capturedAt = capturedAt,
            users = users.values.toList(),
            friends = friendIds,
            groups = groups,
            members = members,
            completeFriendList = true,
            completeGroupList = true,
            completeMemberGroupIds = completeMemberGroupIds,
        )
    }

    private suspend fun executeList(remoteBot: RemoteBot, action: String, params: String? = null): List<JsonObject> {
        val payload = withTimeout(ACTION_TIMEOUT_MS) { remoteBot.executeAction(action, params) }
        return OneBotContactPayloadParser.parseObjectList(action, payload)
    }

    private suspend fun delayBetweenGroups() {
        val delayMs = PluginConfig.contactSnapshotGroupDelayMillis.coerceAtLeast(0)
        if (delayMs > 0) delay(delayMs.milliseconds)
    }

    private fun JsonObject.toFriendSnapshot(
        botId: Long,
        userId: Long,
        updatedAt: Long,
        friend: Friend?,
    ): ContactUserSnapshot = ContactUserSnapshot(
        botId = botId,
        userId = userId,
        nickname = text("nickname", "user_name").ifBlank { friend?.nick.orEmpty() },
        remark = text("remark", "user_remark").ifBlank { friend?.remark.orEmpty() },
        sex = text("sex"),
        age = int("age") ?: 0,
        qLevel = int("level", "qq_level", "qqLevel") ?: 0,
        email = text("email", "eMail"),
        sign = text("longNick", "long_nick", "sign"),
        updatedAt = updatedAt,
    )

    private fun JsonObject.toMemberUserSnapshot(
        botId: Long,
        userId: Long,
        updatedAt: Long,
        member: Member?,
    ): ContactUserSnapshot = ContactUserSnapshot(
        botId = botId,
        userId = userId,
        nickname = text("nickname").ifBlank { member?.nick.orEmpty() },
        sex = text("sex"),
        age = int("age") ?: 0,
        qLevel = int("qq_level", "qqLevel") ?: 0,
        updatedAt = updatedAt,
    )

    private fun JsonObject.toMemberSnapshot(
        botId: Long,
        groupId: Long,
        userId: Long,
        updatedAt: Long,
        member: Member?,
    ): ContactGroupMemberSnapshot = ContactGroupMemberSnapshot(
        botId = botId,
        groupId = groupId,
        userId = userId,
        nickname = text("nickname").ifBlank { member?.nick.orEmpty() },
        nameCard = text("card").ifBlank { member?.nameCard.orEmpty() },
        role = text("role").ifBlank { member?.permission?.name?.lowercase().orEmpty() },
        specialTitle = text("title").ifBlank { member?.let { runCatching { it.specialTitle }.getOrDefault("") }.orEmpty() },
        sex = text("sex"),
        age = int("age") ?: 0,
        area = text("area"),
        level = int("level") ?: 0,
        qLevel = int("qq_level", "qqLevel") ?: 0,
        joinTime = int("join_time") ?: 0,
        lastSpeakTime = int("last_sent_time") ?: 0,
        updatedAt = updatedAt,
    )

    private fun Friend.toUserSnapshot(
        botId: Long,
        updatedAt: Long,
    ): ContactUserSnapshot = onebotJson().toFriendSnapshot(botId, id, updatedAt, this)

    private fun Member.toUserSnapshot(
        botId: Long,
        updatedAt: Long,
        data: JsonObject,
    ): ContactUserSnapshot = data.toMemberUserSnapshot(botId, id, updatedAt, this)

    private fun NormalMember.toMemberSnapshot(
        botId: Long,
        updatedAt: Long,
        data: JsonObject,
    ): ContactGroupMemberSnapshot = data.toMemberSnapshot(botId, group.id, id, updatedAt, this).copy(
        joinTime = data.int("join_time") ?: joinTimestamp,
        lastSpeakTime = data.int("last_sent_time") ?: lastSpeakTimestamp,
    )

    private fun MutableMap<Long, ContactUserSnapshot>.merge(snapshot: ContactUserSnapshot) {
        val current = this[snapshot.userId]
        this[snapshot.userId] = if (current == null) {
            snapshot
        } else {
            snapshot.copy(
                nickname = snapshot.nickname.ifBlank { current.nickname },
                remark = snapshot.remark.ifBlank { current.remark },
                sex = snapshot.sex.ifBlank { current.sex },
                age = snapshot.age.takeIf { it > 0 } ?: current.age,
                qLevel = snapshot.qLevel.takeIf { it > 0 } ?: current.qLevel,
                email = snapshot.email.ifBlank { current.email },
                sign = snapshot.sign.ifBlank { current.sign },
                updatedAt = maxOf(snapshot.updatedAt, current.updatedAt),
            )
        }
    }

    private fun Any.onebotJson(): JsonObject {
        val raw = (this as? RemoteUser)?.onebotData.orEmpty()
        if (raw.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    }
}
