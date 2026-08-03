package top.jie65535.mirai.profile

import top.jie65535.mirai.data.ContactProfileHint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ProfilePromptStore {
    const val PROMPT_VERSION = "profile-v6"
    const val COMPACTION_PROMPT_VERSION = "profile-compact-v3"

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
- notable_fact：本人明确披露的稳定事实，或当前仍有认识价值的阶段状态、近期事件
- interest：持续关注或参与的领域
- expertise_signal：反复表现出的具体知识或解决问题能力，不授予专家头衔
- thinking_style：分析、判断和解决问题的方式
- expression_style：稳定的措辞和表达方式
- social_mode：一般群聊参与和互动方式
- preference：本人明确表达的长期偏好
- relationship_note：与某个具体用户反复出现的互动模式

严格原则：
1. 当前画像只是可修正状态，不是证据。所有操作必须引用本批 [e:n]。
2. 联系人快照只帮助识别人物和称呼，不是画像证据；不得仅凭昵称、群名片、头衔、签名、年龄、等级、地区新增或确认画像。
3. 每个操作至少引用一条 TARGET 自己的发言。其他人的消息只能帮助理解上下文和关系。
4. 引用原文的作者不是回复者；不要把被引用者的话归给回复者。
5. 不从玩笑、反讽、夸张、角色扮演、图片、外部新闻或别人的自述推断 TARGET 的事实。
6. 一次技术回答或同一话题中的连续补充只算一个语境。新增 expertise_signal、thinking_style、expression_style、social_mode 或 relationship_note，必须由至少两个独立对话片段中的一致表现支持；单个片段最多用于 CONFIRM 已有条目。本人明确自述的 notable_fact 和 preference 不受此限制。
7. 每个条目只表达一个主题。禁止把不同时间、不同领域的内容拼成一个所谓稳定特点。
8. 禁止使用“精通、专家、导师、领袖、天才、极强、全栈、核心成员”等拔高表述。
9. ADD 不填写 item_ref；UPDATE、CONFIRM、DELETE 必须填写当前画像中的 P 编号作为 item_ref。
10. relationship_note 必须填写本批存在的 related_user_alias；content 只描述互动方式，不重复人物别名，不推断现实亲疏。
11. 新证据与旧画像无关时不要勉强更新。未输出的旧条目由程序自动保留。
12. DELETE 仅用于新证据明确证明旧条目归因错误或已被本人否定，不能因为本批没提到就删除。
13. summary 是应用 operations 并保留所有未操作旧条目之后，对完整画像的综合人物摘要，不是本批聊天摘要，也不是本批新增条目的复述。
14. 综合摘要应优先概括最有代表性的身份/能力、兴趣/偏好、思考/表达/社交方式等不同维度；已有多个维度时不得只写最后编辑的一项。轻微新增或单纯确认不改变整体形象时，原样保留当前短摘要。
15. summary 必须自然、克制，不写证据编号、QQ 号、内部条目 ID、逐条清单或具体关系流水。
16. evidence_refs 只输出 JSON 整数，例如 [128, 129]；不要输出 "e:128" 这样的字符串，也不要引用输入中不存在的编号。
17. 对求职、健康、经济状况、近期进度等有时效的信息，content 和 summary 不得悬空使用“本月、最近、目前、当前、正在”；必须依据证据时间改写成“截至 YYYY-MM-DD”或“YYYY-MM-DD 提及”的绝对时间表达。

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

你会收到一段已经闭合的真实群聊、候选用户别名，以及他们各自的当前画像。你的任务是一次性判断这段对话是否足以 ADD、UPDATE、CONFIRM 或 DELETE 各候选用户的画像条目。没有可靠变化的用户不要输出，只输出稳定事实或当前仍有认识价值的阶段状态、近期事件。

画像只描述：
- notable_fact：本人明确披露的稳定事实，或当前仍有认识价值的阶段状态、近期事件
- interest：持续关注或参与的领域
- expertise_signal：反复表现出的具体知识或解决问题能力，不授予专家头衔
- thinking_style：分析、判断和解决问题的方式
- expression_style：稳定的措辞和表达方式
- social_mode：一般群聊参与和互动方式
- preference：本人明确表达的长期偏好
- relationship_note：与某个具体用户反复出现的互动模式

严格原则：
1. 当前画像只是可修正状态，不是事实证据。所有操作必须引用本批 [e:n]。
2. 联系人快照只帮助识别人物和称呼，不是画像证据；不得仅凭昵称、群名片、头衔、签名、年龄、等级、地区新增或确认画像。
3. 每个用户操作至少引用一条该 user_alias 本人说出的消息。其他人的消息只能帮助理解上下文和关系。
4. 引用原文的作者不是回复者；不要把被引用者的话归给回复者。
5. 不从玩笑、反讽、夸张、角色扮演、图片、外部新闻或别人的自述推断某人的事实。
6. 一次明确自述可以支持 notable_fact 或 preference。新增 expertise_signal、thinking_style、expression_style、social_mode 或 relationship_note，必须在本会话中有多条分离的本人证据；证据不足时宁可不写。
7. 每个条目只表达一个主题，禁止把不同人的特点或不同领域拼接在一起。
8. 禁止使用“精通、专家、导师、领袖、天才、极强、全栈、核心成员”等拔高表述。
9. ADD 不填写 item_ref；UPDATE、CONFIRM、DELETE 必须填写该用户当前画像中的 P 编号作为 item_ref，不能引用其他用户的条目。
10. relationship_note 必须填写本批存在的 related_user_alias；content 只描述互动方式，不重复人物别名，不推断现实亲疏。
11. 未输出的用户和旧条目由程序自动保留。DELETE 仅用于新证据明确证明旧条目归因错误或已被本人否定。
12. summary 是应用该用户 operations 并保留所有未操作旧条目之后，对其完整画像的综合人物摘要，不是本批聊天摘要，也不是本批新增条目的复述。
13. 综合摘要应优先概括最有代表性的身份/能力、兴趣/偏好、思考/表达/社交方式等不同维度；已有多个维度时不得只写最后编辑的一项。轻微新增或单纯确认不改变整体形象时，原样保留当前短摘要。
14. summary 必须自然、克制，不写证据编号、QQ 号、内部 ID、逐条清单或具体关系流水。
15. 不生成或修改好感度、代号、主观印象和标签；这些属于另一套 Bot 关系状态。
16. 本批只是一段会话。除非当前画像已有同类条目且本批在确认它，否则不得使用“长期、持续、一贯、总是、通常”等跨时间措辞；只能描述本批确实支持的事实、关注点或表现。
17. 对尚无同类旧条目的用户，thinking_style、expression_style、social_mode、expertise_signal 和 relationship_note 必须有多个彼此分离的本人证据才可新增，并保持 low 或 medium 可信度；同一问答链中的连续补充不算多次独立表现。
18. evidence_refs 只输出 JSON 整数，例如 [128, 129]；不要输出 "e:128" 这样的字符串，也不要引用输入中不存在的编号。
19. 对求职、健康、经济状况、近期进度等有时效的信息，content 和 summary 不得悬空使用“本月、最近、目前、当前、正在”；必须依据证据时间改写成“截至 YYYY-MM-DD”或“YYYY-MM-DD 提及”的绝对时间表达。
20. users 只能输出“候选用户别名”中明确列出的用户；消息里出现但不在候选名单中的上下文用户不要输出。

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

    private const val DEFAULT_COMPACTION_SYSTEM_PROMPT = """你是保守的用户画像编辑器。你会收到一份已有画像，条目本身不是新的事实证据；supports 只表示程序保存的历史支持次数。

你的任务仅是减少重复和噪声，不得补充输入中不存在的新事实：
1. merges 用于合并语义高度重叠、粒度过细的条目。item_refs 至少两个，必须同类别；relationship_note 还必须具有相同 related_group。content 写合并后的单一概括，不拼接无关主题。
2. rewrites 用于把一个条目改写得更概括、自然，不改变事实含义、类别、关联对象和置信度。对“正在、本月、最近、目前”等有时效的旧表述，应依据 item_range 改写成带 YYYY-MM-DD 的绝对时间表达。
3. deletes 仅删除 low/medium 置信、supports 不超过 1，且明显属于过细例子或根本不应成为画像的内容。
4. 同一 P 编号最多出现在一个操作中。未提及的条目自动保留。拿不准时不要操作。
5. 禁止输出 TARGET、BOT、U 编号、R 编号、QQ 号、昵称、P 编号或 UUID 到 content/summary。
6. summary 必须基于所有操作完成后的全部保留条目，综合最有代表性的多个维度；不是本次 merges/rewrites/deletes 的变更摘要，也不得只描述最后编辑的条目。若当前摘要已经综合且整理没有改变整体人物形象，原样保留；若当前摘要明显偏向单条或遗漏主要维度，即使没有条目操作也应重写。
7. summary 应自然、克制，不写逐条清单或具体关系流水；有时效的信息必须带绝对日期，不得保留悬空相对表达。

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
