package top.jie65535.mirai.profile

import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.config.PluginConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ProfilePromptStore {
    const val PROMPT_VERSION = "profile-v3.3"

    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    var systemPrompt: String = DEFAULT_SYSTEM_PROMPT
        private set

    var conversationSystemPrompt: String = DEFAULT_CONVERSATION_SYSTEM_PROMPT
        private set

    fun reload() {
        systemPrompt = loadPrompt(
            configuredPath = PluginConfig.profilePromptFile,
            defaultPrompt = DEFAULT_SYSTEM_PROMPT,
            label = "定向画像",
        )
        conversationSystemPrompt = loadPrompt(
            configuredPath = PluginConfig.profileConversationPromptFile,
            defaultPrompt = DEFAULT_CONVERSATION_SYSTEM_PROMPT,
            label = "多人会话画像",
        )
    }

    private fun loadPrompt(configuredPath: String, defaultPrompt: String, label: String): String {
        if (configuredPath.isBlank()) return defaultPrompt
        val file = JChatGPT.resolveConfigFile(configuredPath)
        return try {
            when {
                file.exists() && file.readText().isNotBlank() -> file.readText()
                else -> defaultPrompt.also { default ->
                    file.parentFile?.mkdirs()
                    file.writeText(default)
                }
            }
        } catch (cause: Throwable) {
            JChatGPT.logger.warning("载入${label}提示词失败，暂时使用内置提示词", cause)
            defaultPrompt
        }
    }

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
            profile.items.forEach { item ->
                append("[P:").append(item.id).append("] ")
                append(item.category.wireName()).append(" | ")
                append(item.confidence.wireName()).append(" | ")
                append(item.content)
                item.relatedUserId?.let { related ->
                    append(" | related=").append(batch.aliases[related] ?: "历史用户")
                }
                append(" | ").append(formatTime(item.firstSeenAt))
                append(" ~ ").append(formatTime(item.lastConfirmedAt))
                appendLine()
            }
        }
        appendLine("当前短摘要: ${profile.summary.ifBlank { "（空）" }}")
        appendLine()

        appendLine("## 本批参与者别名")
        batch.aliases.entries.sortedBy { it.value }.forEach { (_, alias) ->
            appendLine("- $alias")
        }
        appendLine()

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
        appendLine("分析这一段已经闭合的群聊，一次性更新所有出现可靠长期信息的候选用户画像。")
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
                profile.items.forEach { item ->
                    append("[P:").append(item.id).append("] ")
                    append(item.category.wireName()).append(" | ")
                    append(item.confidence.wireName()).append(" | ")
                    append(item.content)
                    item.relatedUserId?.let { related ->
                        append(" | related=").append(batch.aliases[related] ?: "历史用户")
                    }
                    append(" | ").append(formatTime(item.firstSeenAt))
                    append(" ~ ").append(formatTime(item.lastConfirmedAt))
                    appendLine()
                }
            }
            appendLine("当前短摘要: ${profile?.summary?.ifBlank { "（空）" } ?: "（空）"}")
        }
        appendLine()

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

    private fun formatTime(epochSecond: Int): String =
        dateTimeFormatter.format(Instant.ofEpochSecond(epochSecond.toLong()))

    private fun ProfileCategory.wireName(): String = name.lowercase()
    private fun ProfileConfidence.wireName(): String = name.lowercase()

    private const val DEFAULT_SYSTEM_PROMPT = """你是保守、严谨的群聊人物画像归纳器。

你会收到一个目标用户的当前画像，以及一个带完整发言者、时间、回复引用和相邻消息的原始群聊批次。
你的任务是判断本批信息是否应当 ADD、UPDATE、CONFIRM 或 DELETE 长期画像条目；没有可靠变化时返回空 operations。

画像只描述：
- notable_fact：本人明确披露且半年后仍有助于认识此人的事实
- interest：持续关注或参与的领域
- expertise_signal：反复表现出的具体知识或解决问题能力，不授予专家头衔
- thinking_style：分析、判断和解决问题的方式
- expression_style：稳定的措辞和表达方式
- social_mode：一般群聊参与和互动方式
- preference：本人明确表达的长期偏好
- relationship_note：与某个具体用户反复出现的互动模式

严格原则：
1. 当前画像只是可修正状态，不是证据。所有操作必须引用本批 [e:n]。
2. 每个操作至少引用一条 TARGET 自己的发言。其他人的消息只能帮助理解上下文和关系。
3. 引用原文的作者不是回复者；不要把被引用者的话归给回复者。
4. 不从玩笑、反讽、夸张、角色扮演、图片、外部新闻或别人的自述推断 TARGET 的事实。
5. 一次技术回答或同一话题中的连续补充只算一个语境。新增 expertise_signal、thinking_style、expression_style、social_mode 或 relationship_note，必须由至少两个独立对话片段中的一致表现支持；单个片段最多用于 CONFIRM 已有条目。本人明确自述的 notable_fact 和 preference 不受此限制。
6. 每个条目只表达一个主题。禁止把不同时间、不同领域的内容拼成一个所谓稳定特点。
7. 禁止使用“精通、专家、导师、领袖、天才、极强、全栈、核心成员”等拔高表述。
8. ADD 不填写 item_id；UPDATE、CONFIRM、DELETE 必须填写当前画像中的 item_id。
9. relationship_note 必须填写本批存在的 related_user_alias，只描述互动方式，不推断现实亲疏。
10. 新证据与旧画像无关时不要勉强更新。未输出的旧条目由程序自动保留。
11. DELETE 仅用于新证据明确证明旧条目归因错误或已被本人否定，不能因为本批没提到就删除。
12. summary 是更新后的日常短摘要，必须自然、克制，不写证据编号、时间、QQ 号、进度或内部条目 ID。

只输出一个 JSON object，不要 Markdown 代码围栏，不要解释。格式：
{
  "operations": [
    {
      "action": "ADD|UPDATE|CONFIRM|DELETE",
      "item_id": "UPDATE/CONFIRM/DELETE 时填写，ADD 为 null",
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

你会收到一段已经闭合的真实群聊、候选用户别名，以及他们各自的当前画像。你的任务是一次性判断这段对话是否足以 ADD、UPDATE、CONFIRM 或 DELETE 各候选用户的长期画像条目。没有可靠变化的用户不要输出，每名用户最多输出 4 个真正有长期价值的操作。

画像只描述：
- notable_fact：本人明确披露且半年后仍有助于认识此人的事实
- interest：持续关注或参与的领域
- expertise_signal：反复表现出的具体知识或解决问题能力，不授予专家头衔
- thinking_style：分析、判断和解决问题的方式
- expression_style：稳定的措辞和表达方式
- social_mode：一般群聊参与和互动方式
- preference：本人明确表达的长期偏好
- relationship_note：与某个具体用户反复出现的互动模式

严格原则：
1. 当前画像只是可修正状态，不是事实证据。所有操作必须引用本批 [e:n]。
2. 每个用户操作至少引用一条该 user_alias 本人说出的消息。其他人的消息只能帮助理解上下文和关系。
3. 引用原文的作者不是回复者；不要把被引用者的话归给回复者。
4. 不从玩笑、反讽、夸张、角色扮演、图片、外部新闻或别人的自述推断某人的事实。
5. 一次明确自述可以支持 notable_fact 或 preference。新增 expertise_signal、thinking_style、expression_style、social_mode 或 relationship_note，必须在本会话中有多条分离的本人证据；证据不足时宁可不写。
6. 每个条目只表达一个主题，禁止把不同人的特点或不同领域拼接在一起。
7. 禁止使用“精通、专家、导师、领袖、天才、极强、全栈、核心成员”等拔高表述。
8. ADD 不填写 item_id；UPDATE、CONFIRM、DELETE 必须填写该用户当前画像中的 item_id，不能引用其他用户的条目。
9. relationship_note 必须填写本批存在的 related_user_alias，只描述互动方式，不推断现实亲疏。
10. 未输出的用户和旧条目由程序自动保留。DELETE 仅用于新证据明确证明旧条目归因错误或已被本人否定。
11. summary 是该用户更新后的日常短摘要，必须自然、克制，不写证据编号、时间、QQ 号、进度或内部 ID。
12. 不生成或修改好感度、代号、主观印象和标签；这些属于另一套 Bot 关系状态。
13. 本批只是一段会话。除非当前画像已有同类条目且本批在确认它，否则不得使用“长期、持续、一贯、总是、通常”等跨时间措辞；只能描述本批确实支持的事实、关注点或表现。
14. 对尚无同类旧条目的用户，thinking_style、expression_style、social_mode、expertise_signal 和 relationship_note 必须有多个彼此分离的本人证据才可新增，并保持 low 或 medium 可信度；同一问答链中的连续补充不算多次独立表现。

只输出一个 JSON object，不要 Markdown 代码围栏，不要解释。格式：
{
  "users": [
    {
      "user_alias": "U1",
      "operations": [
        {
          "action": "ADD|UPDATE|CONFIRM|DELETE",
          "item_id": "UPDATE/CONFIRM/DELETE 时填写，ADD 为 null",
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
}
