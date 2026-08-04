package top.jie65535.mirai.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ProfileCompactionDeleteReason {
    @SerialName("one_off")
    ONE_OFF,

    @SerialName("over_specific")
    OVER_SPECIFIC,

    @SerialName("transient")
    TRANSIENT,

    @SerialName("not_profile")
    NOT_PROFILE,
}

@Serializable
data class ProfileCompactionMerge(
    @SerialName("item_refs")
    val itemRefs: List<String>,
    val content: String,
)

@Serializable
data class ProfileCompactionRewrite(
    @SerialName("item_ref")
    val itemRef: String,
    val content: String,
)

@Serializable
data class ProfileCompactionDelete(
    @SerialName("item_ref")
    val itemRef: String,
    val reason: ProfileCompactionDeleteReason,
)

@Serializable
data class ProfileCompactionResponse(
    val merges: List<ProfileCompactionMerge> = emptyList(),
    val rewrites: List<ProfileCompactionRewrite> = emptyList(),
    val deletes: List<ProfileCompactionDelete> = emptyList(),
    val summary: String = "",
)

data class ProfileItemSupportStats(
    val count: Int,
    val firstSupportedAt: Int,
    val lastSupportedAt: Int,
)

data class ProfileCompactionModelResult(
    val response: ProfileCompactionResponse,
    val rawResponse: String,
    val usage: ProfileTokenUsage,
)

data class ProfileCompactionPlan(
    val reduction: ProfileReduction,
    val supportReassignments: Map<String, String>,
    val mergedGroups: Int,
    val rewrittenItems: Int,
    val deletedItems: Int,
    val repairedItemRanges: Int = 0,
    val skippedOperations: List<String> = emptyList(),
)

data class ProfileCompactionReport(
    val userId: Long,
    val beforeItems: Int,
    val afterItems: Int,
    val mergedGroups: Int,
    val rewrittenItems: Int,
    val deletedItems: Int,
    val repairedItemRanges: Int = 0,
    val summaryChanged: Boolean,
    val skippedOperations: Int,
    val usage: ProfileTokenUsage,
    val profile: UserProfileSnapshot,
    val alreadyRunning: Boolean = false,
    val stopped: Boolean = false,
)
