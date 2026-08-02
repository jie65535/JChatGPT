package top.jie65535.mirai.profile

object ConversationProfileReducer {
    fun reduce(
        profiles: Map<Long, UserProfileSnapshot>,
        batch: ConversationProfileBatch,
        eligibleUserIds: Set<Long>,
        response: ConversationProfileModelResponse,
        model: String,
        promptVersion: String,
        summaryMaxLength: Int,
    ): List<ProfileReduction> {
        val responsesByUserId = response.users.associate { userResponse ->
            val userId = batch.aliasToUserId[userResponse.userAlias]
                ?: throw IllegalArgumentException("模型返回了不存在的用户别名 ${userResponse.userAlias}")
            require(userId in eligibleUserIds) {
                "模型返回了非候选用户别名 ${userResponse.userAlias}"
            }
            require(userResponse.operations.size <= MAX_OPERATIONS_PER_USER) {
                "模型为 ${userResponse.userAlias} 返回了超过 $MAX_OPERATIONS_PER_USER 项画像操作"
            }
            userId to userResponse
        }
        require(responsesByUserId.size == response.users.size) {
            "模型对同一用户返回了多组结果"
        }

        return eligibleUserIds.sorted().map { userId ->
            val userResponse = responsesByUserId[userId]
            UserProfileReducer.reduce(
                current = checkNotNull(profiles[userId]) { "缺少用户 $userId 的当前画像" },
                batch = batch.forUser(userId),
                response = ProfileModelResponse(
                    operations = userResponse?.operations.orEmpty(),
                    summary = userResponse?.summary.orEmpty(),
                ),
                model = model,
                promptVersion = promptVersion,
                summaryMaxLength = summaryMaxLength,
                advanceBackfillCursor = false,
            )
        }
    }

    private const val MAX_OPERATIONS_PER_USER = 4
}
