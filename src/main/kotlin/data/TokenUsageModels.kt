package top.jie65535.mirai.data

data class ModelUsageEvent(
    val timestamp: Long,
    val botId: Long = 0,
    val userId: Long = 0,
    val userNickname: String = "",
    val groupId: Long? = null,
    val groupName: String? = null,
    val endpointLabel: String? = null,
    val modelAlias: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val usageKind: String = "chat",
    val unit: String = "tokens",
    val inputUnits: Long = 0,
    val outputUnits: Long = 0,
    val totalUnits: Long = inputUnits + outputUnits,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
    val cachedTokens: Long = 0,
)

data class TokenUsageRecord(
    val id: Long,
    val timestamp: Long,
    val date: String,
    val botId: Long?,
    val userId: Long,
    val userNickname: String,
    val groupId: Long?,
    val groupName: String?,
    val endpointLabel: String?,
    val provider: String?,
    val model: String?,
    val modelAlias: String? = null,
    val usageKind: String = "chat",
    val unit: String = "tokens",
    val inputUnits: Long = 0,
    val outputUnits: Long = 0,
    val totalUnits: Long = 0,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val cachedTokens: Long,
    val callCount: Int,
    val detailed: Boolean,
)

data class TokenUsageRanking(
    val id: Long,
    val name: String,
    val totalTokens: Long,
)

data class TokenUsageModelTotal(
    val provider: String,
    val model: String,
    val totalTokens: Long,
    val callCount: Int,
)

data class TokenUsageBreakdown(
    val provider: String,
    val model: String,
    val usageKind: String,
    val unit: String,
    val inputUnits: Long,
    val outputUnits: Long,
    val totalUnits: Long,
    val callCount: Int,
)

data class ModelUsageUserTotal(
    val userId: Long,
    val name: String,
    val usageKind: String,
    val unit: String,
    val totalUnits: Long,
    val callCount: Int,
)

data class ModelUsageDailyTotal(
    val date: String,
    val usageKind: String,
    val unit: String,
    val totalUnits: Long,
    val callCount: Int,
)

data class TokenUsageDailyTotal(
    val date: String,
    val totalTokens: Long,
)

data class TokenUsageSummary(
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val cachedTokens: Long,
    val callCount: Int,
    val activeUsers: Int,
    val todayTotal: Long,
    val daily: List<TokenUsageDailyTotal>,
    val topUsers: List<TokenUsageRanking>,
    val topGroups: List<TokenUsageRanking>,
    val models: List<TokenUsageModelTotal>,
    val allCallCount: Int = callCount,
    val allActiveUsers: Int = activeUsers,
    val breakdown: List<TokenUsageBreakdown> = emptyList(),
    val userUsage: List<ModelUsageUserTotal> = emptyList(),
    val usageDaily: List<ModelUsageDailyTotal> = emptyList(),
)
