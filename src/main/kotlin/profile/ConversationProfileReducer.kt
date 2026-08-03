package top.jie65535.mirai.profile

object ConversationProfileReducer {
    private const val MISSING_ITEM_REFERENCE = "P2147483647"

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

    fun reduceRebased(
        expectedProfiles: Map<Long, UserProfileSnapshot>,
        latestProfiles: Map<Long, UserProfileSnapshot>,
        batch: ConversationProfileBatch,
        eligibleUserIds: Set<Long>,
        response: ConversationProfileModelResponse,
        model: String,
        promptVersion: String,
        summaryMaxLength: Int,
    ): List<ProfileReduction> {
        val rebasedResponse = response.copy(
            users = response.users.map { userResponse ->
                val userId = batch.aliasToUserId[userResponse.userAlias]
                val expected = userId?.let(expectedProfiles::get)
                val latest = userId?.let(latestProfiles::get)
                if (userId == null || userId !in eligibleUserIds || expected == null || latest == null) {
                    userResponse
                } else {
                    userResponse.copy(
                        operations = userResponse.operations.map { operation ->
                            operation.rebaseItemReference(expected, latest)
                        }
                    )
                }
            }
        )
        return reduce(
            profiles = latestProfiles,
            batch = batch,
            eligibleUserIds = eligibleUserIds,
            response = rebasedResponse,
            model = model,
            promptVersion = promptVersion,
            summaryMaxLength = summaryMaxLength,
        )
    }

    private fun ProfileModelOperation.rebaseItemReference(
        expected: UserProfileSnapshot,
        latest: UserProfileSnapshot,
    ): ProfileModelOperation {
        if (action == ProfileOperationAction.ADD) return this
        val expectedItem = ProfileItemReferences.resolve(expected, itemRef)
            ?: return copy(itemRef = MISSING_ITEM_REFERENCE)
        val latestReference = ProfileItemReferences.entries(latest)
            .firstOrNull { entry -> entry.item.id == expectedItem.id }
            ?.reference
            ?: MISSING_ITEM_REFERENCE
        return copy(itemRef = latestReference)
    }
}
