package top.jie65535.mirai.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import top.jie65535.mirai.data.ChatMessageRecord

@Serializable
enum class ProfileCategory {
    @SerialName("notable_fact")
    NOTABLE_FACT,

    @SerialName("interest")
    INTEREST,

    @SerialName("expertise_signal")
    EXPERTISE_SIGNAL,

    @SerialName("thinking_style")
    THINKING_STYLE,

    @SerialName("expression_style")
    EXPRESSION_STYLE,

    @SerialName("social_mode")
    SOCIAL_MODE,

    @SerialName("preference")
    PREFERENCE,

    @SerialName("relationship_note")
    RELATIONSHIP_NOTE,
}

@Serializable
enum class ProfileConfidence {
    @SerialName("low")
    LOW,

    @SerialName("medium")
    MEDIUM,

    @SerialName("high")
    HIGH,
}

@Serializable
enum class ProfileOperationAction {
    ADD,
    UPDATE,
    CONFIRM,
    DELETE,
}

enum class ProfileRevisionSource {
    BACKFILL,
    CONVERSATION,
    COMPACTION,
}

data class UserProfileItem(
    val id: String,
    val category: ProfileCategory,
    val content: String,
    val confidence: ProfileConfidence,
    val relatedUserId: Long? = null,
    val firstSeenAt: Int,
    val lastConfirmedAt: Int,
)

data class UserProfileSnapshot(
    val userId: Long,
    val summary: String = "",
    val version: Int = 0,
    val cursorTime: Int,
    val snapshotEndTime: Int,
    val reliable: Boolean = false,
    val model: String = "",
    val promptVersion: String = "",
    val updatedAt: Long = 0,
    val items: List<UserProfileItem> = emptyList(),
)

@Serializable
data class ProfileModelOperation(
    val action: ProfileOperationAction,
    @SerialName("item_ref")
    val itemRef: String? = null,
    val category: ProfileCategory? = null,
    val content: String? = null,
    val confidence: ProfileConfidence? = null,
    @SerialName("related_user_alias")
    val relatedUserAlias: String? = null,
    @SerialName("evidence_refs")
    @Serializable(with = EvidenceReferenceListSerializer::class)
    val evidenceRefs: List<Int> = emptyList(),
)

@Serializable
data class ProfileModelResponse(
    val operations: List<ProfileModelOperation> = emptyList(),
    val summary: String = "",
)

data class ProfilePromptMessage(
    val record: ChatMessageRecord,
    val text: String,
    val evidenceRef: Int? = null,
    val episodeIndex: Int,
)

data class ProfileHistoryBatch(
    val userId: Long,
    val startTime: Int,
    val endTime: Int,
    val messages: List<ProfilePromptMessage>,
    val aliases: Map<Long, String>,
    val inputHash: String,
) {
    val evidenceByRef: Map<Int, ProfilePromptMessage> = messages
        .mapNotNull { message -> message.evidenceRef?.let { it to message } }
        .toMap()

    val aliasToUserId: Map<String, Long> = aliases.entries.associate { (userId, alias) -> alias to userId }

    val targetMessageCount: Int = messages.count { it.record.fromId == userId && it.evidenceRef != null }

    val targetAuthoredTextChars: Int = messages.asSequence()
        .filter { it.record.fromId == userId && it.evidenceRef != null }
        .sumOf { ProfileMessageRenderer.authoredTextLength(it.record) }
}

data class ProfileTokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
)

data class ProfileModelResult(
    val response: ProfileModelResponse,
    val rawResponse: String,
    val usage: ProfileTokenUsage,
)

@Serializable
data class ConversationProfileUserResponse(
    @SerialName("user_alias")
    val userAlias: String,
    val operations: List<ProfileModelOperation> = emptyList(),
    val summary: String = "",
)

@Serializable
data class ConversationProfileModelResponse(
    val users: List<ConversationProfileUserResponse> = emptyList(),
)

data class ConversationProfileModelResult(
    val response: ConversationProfileModelResponse,
    val rawResponse: String,
    val usage: ProfileTokenUsage,
)

data class ConversationProfileBatch(
    val botId: Long,
    val groupId: Long,
    val startTime: Int,
    val endTime: Int,
    val messages: List<ProfilePromptMessage>,
    val aliases: Map<Long, String>,
    val inputHash: String,
) {
    val evidenceByRef: Map<Int, ProfilePromptMessage> = messages
        .mapNotNull { message -> message.evidenceRef?.let { it to message } }
        .toMap()

    val aliasToUserId: Map<String, Long> = aliases.entries.associate { (userId, alias) -> alias to userId }

    val authoredTextCharsByUser: Map<Long, Int> = messages.asSequence()
        .filter { it.evidenceRef != null && it.record.fromId != botId }
        .groupBy { it.record.fromId }
        .mapValues { (_, authored) -> authored.sumOf { ProfileMessageRenderer.authoredTextLength(it.record) } }

    fun forUser(userId: Long): ProfileHistoryBatch = ProfileHistoryBatch(
        userId = userId,
        startTime = startTime,
        endTime = endTime,
        messages = messages,
        aliases = aliases,
        inputHash = inputHash,
    )
}

@Serializable
data class AppliedProfileOperation(
    val action: ProfileOperationAction,
    val itemId: String,
    val category: ProfileCategory,
    val content: String,
    val confidence: ProfileConfidence,
    val relatedUserId: Long?,
    val evidenceRefs: List<Int>,
)

data class ProfileReduction(
    val profile: UserProfileSnapshot,
    val operations: List<AppliedProfileOperation>,
    val skippedOperations: List<String> = emptyList(),
)

data class ProfileAnalysisProgress(
    val batchIndex: Int,
    val startTime: Int,
    val endTime: Int,
    val messageCount: Int,
    val operationCount: Int,
    val skippedOperationCount: Int,
    val usage: ProfileTokenUsage,
)

data class ConversationProfileAnalysisReport(
    val analyzedUsers: Int,
    val processedMessages: Int,
    val appliedOperations: Int,
    val skippedOperations: Int,
    val usage: ProfileTokenUsage,
)

data class GroupProfileCursor(
    val botId: Long,
    val groupId: Long,
    val cursorTime: Int,
    val snapshotEndTime: Int,
    val updatedAt: Long = 0,
)

data class GroupProfileAnalysisProgress(
    val batchIndex: Int,
    val startTime: Int,
    val endTime: Int,
    val messageCount: Int,
    val analyzedUsers: Int,
    val appliedOperations: Int,
    val skippedOperations: Int,
    val usage: ProfileTokenUsage,
)

data class GroupProfileAnalysisReport(
    val botId: Long?,
    val groupId: Long,
    val processedBatches: Int,
    val processedMessages: Int,
    val analyzedUsers: Int,
    val appliedOperations: Int,
    val skippedOperations: Int,
    val usage: ProfileTokenUsage,
    val cursorTime: Int,
    val snapshotEndTime: Int,
    val caughtUp: Boolean,
    val alreadyRunning: Boolean = false,
)

data class ProfileAnalysisReport(
    val userId: Long,
    val processedBatches: Int,
    val processedMessages: Int,
    val appliedOperations: Int,
    val skippedOperations: Int,
    val usage: ProfileTokenUsage,
    val profile: UserProfileSnapshot?,
    val caughtUp: Boolean,
    val alreadyRunning: Boolean = false,
)
