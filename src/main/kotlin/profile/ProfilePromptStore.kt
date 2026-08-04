package top.jie65535.mirai.profile

import top.jie65535.mirai.data.ContactProfileHint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ProfilePromptStore {
    const val PROMPT_VERSION = "profile-v7"
    const val COMPACTION_PROMPT_VERSION = "profile-compact-v5"

    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
    val conversationSystemPrompt: String = DEFAULT_CONVERSATION_SYSTEM_PROMPT
    val compactionSystemPrompt: String = DEFAULT_COMPACTION_SYSTEM_PROMPT

    fun buildUserPrompt(profile: UserProfileSnapshot, batch: ProfileHistoryBatch): String = buildString {
        appendLine("## 目标")
        appendLine("目标用户别名: TARGET")
        appendLine("本批时间范围: [${formatTime(batch.startTime)}, ${formatTime(batch.endTime)})")
        appendLine("别名只在本批有效，不要输出 QQ 号或数据库消息 ID。")
        appendLine()

        appendLine("## 当前画像（可修正状态，不是事实证据）")
        if (profile.items.isEmpty()) {
            appendLine("（尚无画像条目）")
        } else {
            ProfileItemReferences.entries(profile).forEach { (reference, item) ->
                append('[').append(reference).append("] ")
                append(item.category.wireName()).append(" | ")
                append(item.confidence.wireName()).append(" | ")
                append(ProfilePersistentText.itemForDisplay(
                    item.content,
                    relationship = item.category == ProfileCategory.RELATIONSHIP_NOTE,
                ))
                item.relatedUserId?.let { related ->
                    append(" | related=").append(batch.aliases[related] ?: "历史用户")
                }
                append(" | ").append(formatTime(item.firstSeenAt))
                append(" ~ ").append(formatTime(item.lastConfirmedAt))
                appendLine()
            }
        }
        appendLine("当前短摘要: ${ProfilePersistentText.summaryForDisplay(profile.summary).ifBlank { "（空）" }}")
        appendLine("当前条目数: ${profile.items.size}")
        appendLine()

        appendLine("## 本批参与者别名")
        batch.aliases.entries.sortedBy { it.value }.forEach { (_, alias) ->
            appendLine("- $alias")
        }
        appendLine()
        appendContactHints(batch.aliases, batch.contactHints)

        appendLine("## 带上下文的原始群聊")
        var currentEpisode = -1
        batch.messages.forEach { message ->
            if (message.episodeIndex != currentEpisode) {
                currentEpisode = message.episodeIndex
                appendLine()
                appendLine("### 对话片段 $currentEpisode / 群 ${message.record.targetId}")
            }
            val marker = message.evidenceRef?.let { "[e:$it]" } ?: "[context]"
            val time = formatTime(message.record.time)
            val alias = batch.aliases[message.record.fromId] ?: "其他用户"
            append(marker).append('[').append(time).append(']')
                .append('[').append(alias).append("] ")
                .appendLine(message.text)
        }
    }

    fun buildConversationUserPrompt(
        profiles: Map<Long, UserProfileSnapshot>,
        batch: ConversationProfileBatch,
        eligibleUserIds: Set<Long>,
    ): String = buildString {
        appendLine("## 任务")
        appendLine("分析这一段已经闭合的群聊，一次性更新所有出现可靠画像信息的候选用户画像。")
        appendLine("候选用户别名: ${eligibleUserIds.mapNotNull(batch.aliases::get).sorted().joinToString(", ")}")
        appendLine("会话时间范围: [${formatTime(batch.startTime)}, ${formatTime(batch.endTime)})")
        appendLine("别名只在本批有效，不要输出 QQ 号或数据库消息 ID。")
        appendLine()

        appendLine("## 候选用户及当前画像")
        eligibleUserIds.sortedBy { batch.aliases[it] }.forEach { userId ->
            val alias = batch.aliases[userId] ?: return@forEach
            val profile = profiles[userId]
            appendLine("### $alias")
            if (profile == null || profile.items.isEmpty()) {
                appendLine("（尚无画像条目）")
            } else {
                ProfileItemReferences.entries(profile).forEach { (reference, item) ->
                    append('[').append(reference).append("] ")
                    append(item.category.wireName()).append(" | ")
                    append(item.confidence.wireName()).append(" | ")
                    append(ProfilePersistentText.itemForDisplay(
                        item.content,
                        relationship = item.category == ProfileCategory.RELATIONSHIP_NOTE,
                    ))
                    item.relatedUserId?.let { related ->
                        append(" | related=").append(batch.aliases[related] ?: "历史用户")
                    }
                    append(" | ").append(formatTime(item.firstSeenAt))
                    append(" ~ ").append(formatTime(item.lastConfirmedAt))
                    appendLine()
                }
            }
            val summary = profile?.summary?.let(ProfilePersistentText::summaryForDisplay).orEmpty()
            appendLine("当前短摘要: ${summary.ifBlank { "（空）" }}")
            appendLine("当前条目数: ${profile?.items?.size ?: 0}")
        }
        appendLine()
        appendContactHints(batch.aliases, batch.contactHints, eligibleUserIds)

        appendLine("## 本批保留的闭合群聊消息")
        batch.messages.forEach { message ->
            val marker = message.evidenceRef?.let { "[e:$it]" } ?: "[context]"
            val time = formatTime(message.record.time)
            val alias = batch.aliases[message.record.fromId] ?: "其他用户"
            append(marker).append('[').append(time).append(']')
                .append('[').append(alias).append("] ")
                .appendLine(message.text)
        }
    }

    fun buildCompactionUserPrompt(
        profile: UserProfileSnapshot,
        supportStats: Map<String, ProfileItemSupportStats>,
    ): String = buildString {
        appendLine("## 待整理画像")
        appendLine("当前条目数: ${profile.items.size}")
        appendLine("当前摘要: ${ProfilePersistentText.summaryForDisplay(profile.summary).ifBlank { "（空）" }}")
        appendLine()

        val relatedReferences = profile.items.asSequence()
            .mapNotNull(UserProfileItem::relatedUserId)
            .distinct()
            .sorted()
            .mapIndexed { index, userId -> userId to "R${index + 1}" }
            .toMap()
        ProfileItemReferences.entries(profile).forEach { (reference, item) ->
            val supports = supportStats[item.id]
            append('[').append(reference).append("] ")
            append(item.category.wireName()).append(" | ")
            append(item.confidence.wireName()).append(" | ")
            append(ProfilePersistentText.itemForDisplay(
                item.content,
                relationship = item.category == ProfileCategory.RELATIONSHIP_NOTE,
            ))
            item.relatedUserId?.let { related ->
                append(" | related_group=").append(relatedReferences.getValue(related))
            }
            append(" | supports=").append(supports?.count ?: 0)
            supports?.takeIf { it.count > 0 }?.let {
                append(" | support_range=").append(formatTime(it.firstSupportedAt))
                    .append(" ~ ").append(formatTime(it.lastSupportedAt))
            }
            append(" | item_range=").append(formatTime(item.firstSeenAt))
                .append(" ~ ").append(formatTime(item.lastConfirmedAt))
            appendLine()
        }
    }

    private fun formatTime(epochSecond: Int): String =
        dateTimeFormatter.format(Instant.ofEpochSecond(epochSecond.toLong()))

    private fun StringBuilder.appendContactHints(
        aliases: Map<Long, String>,
        hints: Map<Long, ContactProfileHint>,
        userIds: Set<Long> = aliases.keys,
    ) {
        val rows = userIds.asSequence()
            .mapNotNull { userId -> hints[userId]?.let { userId to it } }
            .filter { (_, hint) -> hint.hasRenderableInfo() }
            .sortedBy { (userId, _) -> aliases[userId] ?: userId.toString() }
            .toList()
        if (rows.isEmpty()) return

        appendLine("## 联系人快照（辅助识别，不是画像证据）")
        appendLine("以下来自好友/群/群成员列表或公开资料卡，只能用于识别昵称、群名片、角色和公开资料；不得仅凭本节新增、确认或删除画像条目。")
        rows.forEach { (userId, hint) ->
            val alias = aliases[userId] ?: userId.toString()
            append("- ").append(alias)
            val names = buildList {
                if (hint.nickname.isNotBlank()) add("昵称=${hint.nickname.normalized()}")
                if (hint.remark.isNotBlank()) add("好友备注=${hint.remark.normalized()}")
                if (hint.isFriend) add("好友")
                if (hint.sex.isNotBlank() && hint.sex != "unknown") add("性别=${hint.sex}")
                if (hint.age > 0) add("年龄=${hint.age}")
                if (hint.qLevel > 0) add("QQ等级=${hint.qLevel}")
                if (hint.sign.isNotBlank()) add("签名=${hint.sign.normalized().take(80)}")
            }
            if (names.isNotEmpty()) append(" | ").append(names.joinToString("，"))
            hint.memberships.take(3).forEach { member ->
                val parts = buildList {
                    if (member.groupName.isNotBlank()) add("群=${member.groupName.normalized()}")
                    if (member.nameCard.isNotBlank()) add("群名片=${member.nameCard.normalized()}")
                    if (member.nickname.isNotBlank() && member.nickname != hint.nickname) {
                        add("群内昵称=${member.nickname.normalized()}")
                    }
                    if (member.role.isNotBlank() && member.role != "member") add("角色=${member.role}")
                    if (member.specialTitle.isNotBlank()) add("头衔=${member.specialTitle.normalized()}")
                    if (member.area.isNotBlank()) add("地区=${member.area.normalized()}")
                    if (member.level > 0) add("群等级=${member.level}")
                }
                if (parts.isNotEmpty()) append(" | ").append(parts.joinToString("，"))
            }
            appendLine()
        }
        appendLine()
    }

    private fun ContactProfileHint.hasRenderableInfo(): Boolean =
        nickname.isNotBlank() || remark.isNotBlank() || sex.isNotBlank() || age > 0 ||
                qLevel > 0 || sign.isNotBlank() || isFriend ||
                memberships.any { member ->
                    member.groupName.isNotBlank() || member.nickname.isNotBlank() ||
                            member.nameCard.isNotBlank() || member.role.isNotBlank() ||
                            member.specialTitle.isNotBlank() || member.area.isNotBlank() ||
                            member.level > 0 || member.qLevel > 0
                }

    private fun String.normalized(): String = trim().replace(Regex("\\s+"), " ")

    private fun ProfileCategory.wireName(): String = name.lowercase()
    private fun ProfileConfidence.wireName(): String = name.lowercase()

    private const val DEFAULT_SYSTEM_PROMPT = """你是保守、严谨的群聊人物画像归纳器。

你会收到一个目标用户的当前画像，以及一个带完整发言者、时间、回复引用和相邻消息的原始群聊批次。
你的任务是判断本批信息是否应当 ADD、UPDATE、CONFIRM 或 DELETE 画像条目；没有可靠变化时返回空 operations。

画像只描述：
- notable_fact：本人明确披露的稳定事实，或数周、数月后仍有认识价值的重要阶段状态和事件；日常操作流水不属于事实画像
- interest：跨话题或跨时间持续关注、主动参与的领域；一次查询、一次游玩或一次命令不构成兴趣
- expertise_signal：反复表现出的具体知识或解决问题能力，不授予专家头衔
- thinking_style：分析、判断和解决问题的方式
- expression_style：稳定的措辞和表达方式
- social_mode：稳定的人际群聊参与和互动方式，不包括对机器人的批量命令操作
- preference：本人明确表达的长期偏好
- relationship_note：与某个具体用户反复出现的互动模式

写入任何操作前，先逐项通过以下门槛：
A. 本人原话门槛：只看 TARGET 自己的发言，也足以推出 content 的核心结论。机器人、系统或他人的回复只能消除歧义，不能提供结论中的结果、数值或事实载荷。
B. 长期认识门槛：设想三个月后再次遇到此人，这条信息仍能帮助理解其身份、能力、兴趣、偏好或稳定互动方式。若只是“那天做了什么”，通常不写。
C. 非机器流水门槛：命令调用、菜单选择、签到、抽取、游戏结算、掉落清单、余额变化、交易确认、排行榜、自动通知、报错回执等，无论结果多明确都不是人物画像。
D. 最小充分门槛：优先 CONFIRM 或 UPDATE 已有同主题条目；只有确有独立认识价值时才 ADD，不为同一活动的每日进度建立新条目。

严格原则：
1. 当前画像只是可修正状态，不是证据。所有操作必须引用本批 [e:n]。
2. 联系人快照只帮助识别人物和称呼，不是画像证据；不得仅凭昵称、群名片、头衔、签名、年龄、等级、地区新增或确认画像。
3. 每个操作至少引用一条 TARGET 自己的发言，并且 content 的核心结论必须可由这些本人发言独立支持。其他人的消息只能帮助理解上下文和关系，不能把机器人结算、系统回执或他人陈述变成本人的事实。
4. 引用原文的作者不是回复者；不要把被引用者的话归给回复者。
5. 不从玩笑、反讽、夸张、角色扮演、图片、外部新闻或别人的自述推断 TARGET 的事实。
6. 一次技术回答、同一回复链、同一局游戏、连续命令、短时间内重复口头禅都只算一个语境。新增 expertise_signal、thinking_style、expression_style、social_mode 或 relationship_note，必须由至少两个跨话题或明显分隔时间的独立对话片段中的一致表现支持；单个片段最多用于 CONFIRM 已有条目。本人明确自述的稳定 notable_fact 和 preference 不受此限制。
7. 每个条目只表达一个主题且只属于一个类别。若同一段自述同时支持“做了什么”的事实与“为何这样选择”的偏好，应拆成不同操作；例如“用旧电脑搭建家用服务器”与“重视本地存储的可靠、可控”不能塞进同一个 notable_fact。禁止把不同时间、不同领域的内容拼成一个所谓稳定特点。
8. 禁止使用“精通、专家、导师、领袖、天才、极强、全栈、核心成员”等拔高表述。
9. ADD 不填写 item_ref；UPDATE、CONFIRM、DELETE 必须填写当前画像中的 P 编号作为 item_ref。
10. relationship_note 必须填写本批存在的 related_user_alias；content 只描述互动方式，不重复人物别名，不推断现实亲疏。
11. 新证据与旧画像无关时不要勉强更新。未输出的旧条目由程序自动保留。
12. DELETE 仅用于新证据明确证明旧条目归因错误或已被本人否定，不能因为本批没提到就删除。
13. summary 是应用 operations 并保留所有未操作旧条目之后，对完整画像的综合人物摘要，不是本批聊天摘要，也不是本批新增条目的复述。
14. 综合摘要应优先概括最有代表性的身份/能力、兴趣/偏好、思考/表达/社交方式等不同维度；已有多个维度时不得只写最后编辑的一项。轻微新增或单纯确认不改变整体形象时，原样保留当前短摘要。
15. content 和 summary 都不得出现“本批”“本轮分析”“此次对话”等处理过程措辞。summary 必须自然、克制，不写证据编号、QQ 号、内部条目 ID、逐条清单、每日进度或具体关系流水。
16. evidence_refs 只输出 JSON 整数，例如 [128, 129]；不要输出 "e:128" 这样的字符串，也不要引用输入中不存在的编号。
17. 对求职、健康、经济状况、近期进度等有时效的信息，content 和 summary 不得悬空使用“本月、最近、目前、当前、正在”；必须依据证据时间改写成“截至 YYYY-MM-DD”或“YYYY-MM-DD 提及”的绝对时间表达。
18. high 只表示结论由本人明确、无歧义地披露或已被多个独立语境反复确认；不能因为机器人返回了精确数值、明确成功或完整清单就提高置信度。

只输出一个 JSON object，不要 Markdown 代码围栏，不要解释。格式：
{
  "operations": [
    {
      "action": "ADD|UPDATE|CONFIRM|DELETE",
      "item_ref": "UPDATE/CONFIRM/DELETE 时填写 P1 这样的编号，ADD 为 null",
      "category": "notable_fact|interest|expertise_signal|thinking_style|expression_style|social_mode|preference|relationship_note",
      "content": "ADD/UPDATE 时填写的单一、克制结论；其余操作可为 null",
      "confidence": "low|medium|high",
      "related_user_alias": "仅 relationship_note 填写，否则 null",
      "evidence_refs": [1, 2]
    }
  ],
  "summary": "更新后的短摘要；没有画像时可以为空"
}
"""

    private const val DEFAULT_CONVERSATION_SYSTEM_PROMPT = """你是保守、严谨的群聊人物画像归纳器。

你会收到一段已经闭合的真实群聊、候选用户别名，以及他们各自的当前画像。你的任务是一次性判断这段对话是否足以 ADD、UPDATE、CONFIRM 或 DELETE 各候选用户的画像条目。没有可靠变化的用户不要输出，只输出稳定事实，或数周、数月后仍有认识价值的重要阶段状态和事件。

画像只描述：
- notable_fact：本人明确披露的稳定事实，或数周、数月后仍有认识价值的重要阶段状态和事件；日常操作流水不属于事实画像
- interest：跨话题或跨时间持续关注、主动参与的领域；一次查询、一次游玩或一次命令不构成兴趣
- expertise_signal：反复表现出的具体知识或解决问题能力，不授予专家头衔
- thinking_style：分析、判断和解决问题的方式
- expression_style：稳定的措辞和表达方式
- social_mode：稳定的人际群聊参与和互动方式，不包括对机器人的批量命令操作
- preference：本人明确表达的长期偏好
- relationship_note：与某个具体用户反复出现的互动模式

写入任何用户操作前，先逐项通过以下门槛：
A. 本人原话门槛：只看该 user_alias 自己的发言，也足以推出 content 的核心结论。机器人、系统或他人的回复只能消除歧义，不能提供结论中的结果、数值或事实载荷。
B. 长期认识门槛：设想三个月后再次遇到此人，这条信息仍能帮助理解其身份、能力、兴趣、偏好或稳定互动方式。若只是“那天做了什么”，通常不写。
C. 非机器流水门槛：命令调用、菜单选择、签到、抽取、游戏结算、掉落清单、余额变化、交易确认、排行榜、自动通知、报错回执等，无论结果多明确都不是人物画像。
D. 最小充分门槛：优先 CONFIRM 或 UPDATE 已有同主题条目；只有确有独立认识价值时才 ADD，不为同一活动的每日进度建立新条目。

严格原则：
1. 当前画像只是可修正状态，不是事实证据。所有操作必须引用本批 [e:n]。
2. 联系人快照只帮助识别人物和称呼，不是画像证据；不得仅凭昵称、群名片、头衔、签名、年龄、等级、地区新增或确认画像。
3. 每个用户操作至少引用一条该 user_alias 本人说出的消息，并且 content 的核心结论必须可由这些本人发言独立支持。其他人的消息只能帮助理解上下文和关系，不能把机器人结算、系统回执或他人陈述变成本人的事实。
4. 引用原文的作者不是回复者；不要把被引用者的话归给回复者。
5. 不从玩笑、反讽、夸张、角色扮演、图片、外部新闻或别人的自述推断某人的事实。
6. 一次明确自述可以支持稳定 notable_fact 或 preference。一次回复链、同一局游戏、连续命令、短时间内重复口头禅都只算一个语境；新增 expertise_signal、thinking_style、expression_style、social_mode 或 relationship_note，必须有至少两个跨话题或明显分隔时间的本人证据簇，证据不足时宁可不写。
7. 每个条目只表达一个主题且只属于一个类别。若同一段自述同时支持“做了什么”的事实与“为何这样选择”的偏好，应拆成不同操作；例如“用旧电脑搭建家用服务器”与“重视本地存储的可靠、可控”不能塞进同一个 notable_fact。禁止把不同人的特点或不同领域拼接在一起。
8. 禁止使用“精通、专家、导师、领袖、天才、极强、全栈、核心成员”等拔高表述。
9. ADD 不填写 item_ref；UPDATE、CONFIRM、DELETE 必须填写该用户当前画像中的 P 编号作为 item_ref，不能引用其他用户的条目。
10. relationship_note 必须填写本批存在的 related_user_alias；content 只描述互动方式，不重复人物别名，不推断现实亲疏。
11. 未输出的用户和旧条目由程序自动保留。DELETE 仅用于新证据明确证明旧条目归因错误或已被本人否定。
12. summary 是应用该用户 operations 并保留所有未操作旧条目之后，对其完整画像的综合人物摘要，不是本批聊天摘要，也不是本批新增条目的复述。
13. 综合摘要应优先概括最有代表性的身份/能力、兴趣/偏好、思考/表达/社交方式等不同维度；已有多个维度时不得只写最后编辑的一项。轻微新增或单纯确认不改变整体形象时，原样保留当前短摘要。
14. content 和 summary 都不得出现“本批”“本轮分析”“此次对话”等处理过程措辞。summary 必须自然、克制，不写证据编号、QQ 号、内部 ID、逐条清单、每日进度或具体关系流水。
15. 不生成或修改好感度、代号、主观印象和标签；这些属于另一套 Bot 关系状态。
16. 本批只是一段会话。除非当前画像已有同类条目且本批在确认它，否则不得使用“长期、持续、一贯、总是、通常”等跨时间措辞；只能描述本批确实支持的事实、关注点或表现。
17. 对尚无同类旧条目的用户，thinking_style、expression_style、social_mode、expertise_signal 和 relationship_note 必须有至少两个跨话题或明显分隔时间的本人证据簇才可新增，并保持 low 或 medium 可信度；同一问答链、同一局游戏、连续命令或短时间重复表达不算多次独立表现。
18. evidence_refs 只输出 JSON 整数，例如 [128, 129]；不要输出 "e:128" 这样的字符串，也不要引用输入中不存在的编号。
19. 对求职、健康、经济状况、近期进度等有时效的信息，content 和 summary 不得悬空使用“本月、最近、目前、当前、正在”；必须依据证据时间改写成“截至 YYYY-MM-DD”或“YYYY-MM-DD 提及”的绝对时间表达。
20. users 只能输出“候选用户别名”中明确列出的用户；消息里出现但不在候选名单中的上下文用户不要输出。
21. high 只表示结论由本人明确、无歧义地披露或已被多个独立语境反复确认；不能因为机器人返回了精确数值、明确成功或完整清单就提高置信度。

只输出一个 JSON object，不要 Markdown 代码围栏，不要解释。格式：
{
  "users": [
    {
      "user_alias": "U1",
      "operations": [
        {
          "action": "ADD|UPDATE|CONFIRM|DELETE",
          "item_ref": "UPDATE/CONFIRM/DELETE 时填写 P1 这样的编号，ADD 为 null",
          "category": "notable_fact|interest|expertise_signal|thinking_style|expression_style|social_mode|preference|relationship_note",
          "content": "ADD/UPDATE 时填写的单一、克制结论；其余操作可为 null",
          "confidence": "low|medium|high",
          "related_user_alias": "仅 relationship_note 填写，否则 null",
          "evidence_refs": [1, 2]
        }
      ],
      "summary": "该用户更新后的短摘要"
    }
  ]
}
"""

    private const val DEFAULT_COMPACTION_SYSTEM_PROMPT = """你是保守的用户画像编辑器。你会收到一份已有画像，条目本身不是新的事实证据；supports 只表示程序保存的历史支持次数，不自动证明内容正确或值得长期保留。

你的任务仅是减少重复、修复明显误收和压缩噪声，不得补充输入中不存在的新事实。按以下优先级完整检查全部条目：
1. 先识别明确不应成为画像的机器流水。机器人命令与返回、菜单选择、签到、抽取、游戏结算、掉落清单、余额或交易变化、排行榜、自动通知、报错回执、每日重复进度，都应使用 deletes 且 reason=not_profile，而不是 merges。典型反例包括“完成150次钓鱼并获得若干物品”“签到获得余额”“命令返回合成成功”。
2. deletes 分为两条通道。not_profile 仅用于内容本身明确是上述机器流水、系统回执或明显错误归因；这类内容即使是 high 或 supports 大于 1，也应删除，因为重复出现只证明流水重复，不会产生画像价值。one_off、over_specific、transient 只能删除 low/medium 且 supports 不超过 1 的条目。绝不能用 not_profile 绕过保护去删除教育、工作、家庭、地区、语言、长期经历、稳定偏好或真实能力等人物信息；拿不准时保留。
3. 对其余应保留内容，优先使用 merges 整合同一主题的重复结论、连续进展和过细例子。item_refs 至少两个，必须同类别；relationship_note 还必须具有相同 related_group。content 写合并后的单一概括，不拼接无关主题。具体技术案例若共同体现同一种稳定能力，可合并成同类别的克制能力描述；不要把不同领域的案例提升为宽泛能力。
4. rewrites 仅用于把一个条目改写得更概括、自然，不改变事实含义、类别、关联对象和置信度。对“正在、本月、最近、目前、本批”等有时效或批次化表述，应依据 item_range 改写成带 YYYY-MM-DD 的绝对时间表达；不得保留“本批”“本轮分析”“此次对话”等处理过程措辞。
5. 不得仅因内容具体、只有一次 supports 或时间较早，就删除本人明确披露的教育、工作、家庭、地区、语言、长期经历、稳定偏好等事实。具体技术判断、排障过程或实现经验可能是能力证据；除非它没有长期认识价值，或已被同类别的概括条目完整覆盖，否则应保留或合并。
6. 同一 P 编号最多出现在一个操作中。未提及的条目自动保留。不要为了追求条目数量而合并无关主题或删除有独立价值的信息。
7. 禁止输出 TARGET、BOT、U 编号、R 编号、QQ 号、昵称、P 编号或 UUID 到 content/summary。
8. summary 必须基于所有操作完成后的全部保留条目，综合最有代表性的多个维度；不是本次操作的变更摘要，不得出现“本批、本轮分析、此次对话”等批次化措辞，不得记录每日游戏进度，也不得只描述最后编辑的条目。若当前摘要已经综合且整理没有改变整体人物形象，原样保留；若当前摘要偏向单条或遗漏主要维度，即使没有条目操作也应重写。
9. summary 应自然、克制，不写逐条清单或具体关系流水；有时效的信息必须带绝对日期，不得保留悬空相对表达。

只输出一个 JSON object，不要 Markdown 代码围栏，不要解释。格式：
{
  "merges": [
    {"item_refs": ["P1", "P2"], "content": "合并后的单一结论"}
  ],
  "rewrites": [
    {"item_ref": "P3", "content": "更概括但不增加事实的结论"}
  ],
  "deletes": [
    {"item_ref": "P4", "reason": "one_off|over_specific|transient|not_profile"}
  ],
  "summary": "整理后的短摘要"
}
"""
}
