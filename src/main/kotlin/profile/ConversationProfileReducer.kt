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
        val responsesByUserId = linkedMapOf<Long, MutableList<ConversationProfileUserResponse>>()
        response.users.forEach { userResponse ->
            val userId = batch.aliasToUserId[userResponse.userAlias] ?: return@forEach
            if (userId !in eligibleUserIds) return@forEach
            responsesByUserId.getOrPut(userId, ::mutableListOf) += userResponse
        }

        return eligibleUserIds.sorted().map { userId ->
            val userResponses = responsesByUserId[userId].orEmpty()
            UserProfileReducer.reduce(
                current = checkNotNull(profiles[userId]) { "缺少用户 $userId 的当前画像" },
                batch = batch.forUser(userId),
                response = ProfileModelResponse(
                    operations = userResponses.flatMap(ConversationProfileUserResponse::operations).distinct(),
                    summary = userResponses.lastOrNull { it.summary.isNotBlank() }?.summary.orEmpty(),
                ),
                model = model,
                promptVersion = promptVersion,
                summaryMaxLength = summaryMaxLength,
                advanceBackfillCursor = false,
            )
        }
    }
}
