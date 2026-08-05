package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import net.mamoe.mirai.contact.User
import net.mamoe.mirai.contact.nameCardOrNick
import net.mamoe.mirai.data.UserProfile
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageEvent
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.config.PluginConfig
import top.jie65535.mirai.data.ContactSnapshotStore
import top.jie65535.mirai.data.PluginData
import top.jie65535.mirai.profile.ProfileCategory
import top.jie65535.mirai.profile.ProfileConfidence
import top.jie65535.mirai.profile.ProfileItemSupportStats
import top.jie65535.mirai.profile.ProfilePersistentText
import top.jie65535.mirai.profile.UserProfileItem
import top.jie65535.mirai.profile.UserProfileSnapshot
import top.jie65535.mirai.profile.UserProfileStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

class QueryUserProfileAgent : BaseAgent(
    tool = Tool.function(
        name = "queryUserProfile",
        description = "查询你已归纳的用户画像，并按需读取该联系人的公开资料卡。用于了解某个群友/好友的长期信息、兴趣、表达方式、关系备注。默认查询当前对话发送者；在群聊中也可用QQ号、群名片或昵称查询别人。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("userId") {
                    put("type", "integer")
                    put("description", "要查询的用户QQ号；留空时查询当前发送者")
                }
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "群名片、昵称或好友备注关键词；仅在未填写userId时用于群聊中匹配用户")
                }
                putJsonObject("includeItems") {
                    put("type", "boolean")
                    put("description", "是否返回画像条目明细，默认true")
                }
            }
        }
    )
) {
    override val isEnabled: Boolean
        get() = PluginConfig.profileEnabled && UserProfileStore.isAvailable

    override val loadingMessage: String
        get() = "查询用户画像中..."

    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        val userId = resolveUserId(args, event)
            ?: return "未找到唯一匹配用户。请提供 userId，或使用更明确的群名片、昵称、好友备注。"
        val (profile, supportStats) = runCatching {
            val snapshot = UserProfileStore.load(userId)
            snapshot to if (snapshot == null) emptyMap() else UserProfileStore.loadSupportStats(userId)
        }
            .getOrElse { cause -> return "读取用户 $userId 画像失败：${cause.message ?: cause::class.simpleName}" }
        val publicProfile = loadPublicProfile(userId, event)
        if (profile == null && publicProfile == null) return "用户 $userId 尚无画像，当前联系人也没有可读取的公开资料卡。"

        val includeItems = args?.get("includeItems")?.jsonPrimitive?.booleanOrNull ?: true
        val displayName = resolveDisplayName(userId, event)

        return formatProfile(userId, profile, supportStats, publicProfile, displayName, includeItems)
    }

    private fun resolveUserId(args: JsonObject?, event: MessageEvent): Long? {
        args?.get("userId")?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }?.let { return it }
        val name = args?.get("name")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (name.isBlank()) return event.sender.id
        name.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        if (event is GroupMessageEvent) {
            selectUniqueNameMatch(
                event.group.members.map { member -> member.id to listOf(member.nameCardOrNick, member.nick) },
                name,
            )?.let { return it }
        }
        val groupId = (event as? GroupMessageEvent)?.group?.id
        val snapshotMatches = ContactSnapshotStore.findUsersByName(event.bot.id, groupId, name, limit = 10)
        val bestRank = snapshotMatches.firstOrNull()?.matchRank ?: return null
        return snapshotMatches.asSequence()
            .takeWhile { match -> match.matchRank == bestRank }
            .map { match -> match.userId }
            .distinct()
            .singleOrNull()
    }

    private fun resolveDisplayName(userId: Long, event: MessageEvent): String {
        val favorabilityName = PluginData.userFavorability[userId]?.name.orEmpty()
        if (favorabilityName.isNotBlank()) return favorabilityName
        if (event is GroupMessageEvent) {
            event.group[userId]?.nameCardOrNick?.takeIf(String::isNotBlank)?.let { return it }
        }
        ContactSnapshotStore.loadDisplayName(
            event.bot.id,
            (event as? GroupMessageEvent)?.group?.id,
            userId,
        )?.let { return it }
        return userId.toString()
    }

    private fun selectUniqueNameMatch(
        candidates: Collection<Pair<Long, List<String>>>,
        query: String,
    ): Long? {
        val ranked = candidates.mapNotNull { (userId, names) ->
            val rank = names.asSequence()
                .filter(String::isNotBlank)
                .map { name ->
                    when {
                        name.equals(query, ignoreCase = true) -> 0
                        name.startsWith(query, ignoreCase = true) -> 1
                        name.contains(query, ignoreCase = true) -> 2
                        else -> Int.MAX_VALUE
                    }
                }
                .minOrNull()
                ?.takeIf { it < Int.MAX_VALUE }
                ?: return@mapNotNull null
            userId to rank
        }
        val bestRank = ranked.minOfOrNull(Pair<Long, Int>::second) ?: return null
        return ranked.asSequence()
            .filter { (_, rank) -> rank == bestRank }
            .map(Pair<Long, Int>::first)
            .distinct()
            .singleOrNull()
    }

    private fun formatProfile(
        userId: Long,
        profile: UserProfileSnapshot?,
        supportStats: Map<String, ProfileItemSupportStats>,
        publicProfile: UserProfile?,
        displayName: String,
        includeItems: Boolean,
    ): String = buildString {
        appendLine("用户画像：$displayName($userId)")
        if (profile != null) {
            appendLine("版本：v${profile.version}；可靠：${if (profile.reliable) "是" else "否"}；条目数：${profile.items.size}")
        }
        if (profile != null && profile.cursorTime > 0) {
            appendLine("历史回顾覆盖至：${formatTime(profile.cursorTime)}")
        } else {
            appendLine("历史回顾：尚未开始或来自自动会话归纳")
        }
        appendLine("摘要：${profile?.summary?.let(ProfilePersistentText::summaryForDisplay).orEmpty().ifBlank { "（暂无）" }}")
        publicProfile?.let { appendPublicProfile(it) }
        if (includeItems && profile?.items?.isNotEmpty() == true) {
            appendLine("条目：")
            selectProfileItems(profile.items)
                .forEach { item ->
                    append("- ").appendLine(formatProfileItem(item, supportStats[item.id]))
                }
        }
    }.trim()

    internal fun formatProfileItem(
        item: UserProfileItem,
        supportStats: ProfileItemSupportStats?,
    ): String = buildString {
        item.relatedUserId?.let { append("与用户 ").append(it).append("：") }
        append(ProfilePersistentText.itemForDisplay(
            item.content,
            relationship = item.category == ProfileCategory.RELATIONSHIP_NOTE,
        ))
        append("〔").append(item.category.label()).append('，').append(item.confidence.label())
            .append('；').append(formatSupportStats(supportStats))
            .append("；最近确认于 ").append(formatDate(item.lastConfirmedAt)).append("〕")
    }

    private suspend fun loadPublicProfile(userId: Long, event: MessageEvent): UserProfile? {
        val key = "${event.bot.id}:$userId"
        val now = System.currentTimeMillis()
        PUBLIC_PROFILE_CACHE[key]?.takeIf { it.expiresAt > now }?.let { return it.profile }
        val user = resolveContact(userId, event) ?: return null
        val profile = withTimeoutOrNull(PUBLIC_PROFILE_TIMEOUT_MS) {
            try {
                user.queryProfile()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                JChatGPT.logger.debug(
                    "按需读取公开资料卡失败: bot=${event.bot.id}, user=$userId, cause=${cause.message}"
                )
                null
            }
        }
        PUBLIC_PROFILE_CACHE[key] = CachedPublicProfile(
            profile = profile,
            expiresAt = now + PUBLIC_PROFILE_CACHE_MS,
        )
        return profile
    }

    private fun resolveContact(userId: Long, event: MessageEvent): User? {
        if (event.sender.id == userId) return event.sender
        if (event is GroupMessageEvent) event.group[userId]?.let { return it }
        return event.bot.friends[userId]
    }

    private fun StringBuilder.appendPublicProfile(profile: UserProfile) {
        val fields = buildList {
            profile.sex.toString().takeUnless { it.equals("unknown", ignoreCase = true) }?.let { add("性别=$it") }
            profile.age.takeIf { it > 0 }?.let { add("年龄=$it") }
            profile.qLevel.takeIf { it > 0 }?.let { add("QQ等级=$it") }
            profile.email.takeIf(String::isNotBlank)?.let { add("邮箱=$it") }
            profile.sign.takeIf(String::isNotBlank)?.let { add("签名=${it.normalized().take(120)}") }
        }
        if (fields.isNotEmpty()) appendLine("公开资料卡：${fields.joinToString("；")}")
    }

    private fun ProfileCategory.label(): String = when (this) {
        ProfileCategory.NOTABLE_FACT -> "事实"
        ProfileCategory.INTEREST -> "兴趣"
        ProfileCategory.EXPERTISE_SIGNAL -> "能力"
        ProfileCategory.THINKING_STYLE -> "思考"
        ProfileCategory.EXPRESSION_STYLE -> "表达"
        ProfileCategory.SOCIAL_MODE -> "社交"
        ProfileCategory.PREFERENCE -> "偏好"
        ProfileCategory.RELATIONSHIP_NOTE -> "关系"
    }

    private fun ProfileConfidence.label(): String = when (this) {
        ProfileConfidence.LOW -> "低置信"
        ProfileConfidence.MEDIUM -> "中置信"
        ProfileConfidence.HIGH -> "高置信"
    }

    private fun formatTime(epochSecond: Int): String =
        TIME_FORMATTER.format(Instant.ofEpochSecond(epochSecond.toLong()))

    private fun formatDate(epochSecond: Int): String =
        DATE_FORMATTER.format(Instant.ofEpochSecond(epochSecond.toLong()))

    internal fun formatSupportStats(stats: ProfileItemSupportStats?): String =
        "${stats?.count ?: 0}次支持"

    companion object {
        private const val PUBLIC_PROFILE_TIMEOUT_MS = 5_000L
        private const val PUBLIC_PROFILE_CACHE_MS = 10 * 60_000L
        private val PUBLIC_PROFILE_CACHE = ConcurrentHashMap<String, CachedPublicProfile>()
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault())
    }

    private data class CachedPublicProfile(
        val profile: UserProfile?,
        val expiresAt: Long,
    )

    private fun String.normalized(): String = trim().replace(Regex("\\s+"), " ")
}

internal fun selectProfileItems(
    items: List<UserProfileItem>,
): List<UserProfileItem> = items.sortedWith(
        compareBy<UserProfileItem>({ it.category.ordinal }, { it.firstSeenAt }, { it.id })
    )
