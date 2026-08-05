package top.jie65535.mirai.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.config.PluginConfig
import top.jie65535.mirai.data.ChatHistoryStore
import top.jie65535.mirai.llm.LargeLanguageModels
import top.jie65535.mirai.llm.ModelRequestRejectedException
import top.jie65535.mirai.llm.ModelSafetyRejectionException
import top.jie65535.mirai.util.RetryBackoff
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object UserProfileAnalysisService {
    private val runningUsers = ConcurrentHashMap.newKeySet<Long>()
    private val runningGroups = ConcurrentHashMap.newKeySet<Long>()
    private val runningCompactions = ConcurrentHashMap.newKeySet<Long>()
    private val userLocks = ProfileUserLockManager()
    private val runGate = ProfileAnalysisRunGate()

    fun newRunToken(): ProfileAnalysisRunToken = runGate.newToken()

    fun stopAll(): ProfileAnalysisStopReport {
        val report = ProfileAnalysisStopReport(
            userTasks = runningUsers.size,
            groupTasks = runningGroups.size,
            compactionTasks = runningCompactions.size,
        )
        runGate.stopCurrentRuns()
        return report
    }

    suspend fun listPendingHistoryGroupIds(
        minimumMessages: Int = PluginConfig.profileBulkGroupMinPendingMessages,
    ): List<Long> {
        check(PluginConfig.profileEnabled) { "历史用户画像分析未启用" }
        check(UserProfileStore.isAvailable) { "用户画像数据库不可用" }
        return withContext(Dispatchers.IO) {
            val reader = ProfileHistoryReader(resolveHistoryFile())
            val historyBounds = reader.listGroupTimeBounds()
            val cursors = UserProfileStore.loadGroupCursors()
                .associateBy { cursor -> cursor.botId to cursor.groupId }
            val pendingRanges = historyBounds.mapNotNull { bounds ->
                pendingGroupAnalysisRange(bounds, cursors[bounds.botId to bounds.groupId])
            }
            reader.filterGroupRangesByMinimumMessageCount(pendingRanges, minimumMessages)
                .asSequence()
                .map(ProfileHistoryReader.GroupTimeBounds::groupId)
                .toList()
        }
    }

    suspend fun listRecentPendingHistoryGroupIds(oldestAllowedMessageTime: Int): List<Long> {
        require(oldestAllowedMessageTime >= 0) { "oldestAllowedMessageTime must not be negative" }
        check(PluginConfig.profileEnabled) { "历史用户画像分析未启用" }
        check(UserProfileStore.isAvailable) { "用户画像数据库不可用" }
        return withContext(Dispatchers.IO) {
            val reader = ProfileHistoryReader(resolveHistoryFile())
            val historyBounds = reader.listGroupTimeBounds()
            val cursors = UserProfileStore.loadGroupCursors()
                .associateBy { cursor -> cursor.botId to cursor.groupId }
            val pendingRanges = historyBounds.mapNotNull { bounds ->
                pendingGroupAnalysisRange(bounds, cursors[bounds.botId to bounds.groupId])
            }
            reader.filterGroupRangesByOldestPendingMessageTime(
                ranges = pendingRanges,
                oldestAllowedTime = oldestAllowedMessageTime,
            ).map(ProfileHistoryReader.GroupTimeBounds::groupId)
        }
    }

    suspend fun analyze(
        userId: Long,
        maxBatches: Int,
        runToken: ProfileAnalysisRunToken = newRunToken(),
        onProgress: suspend (ProfileAnalysisProgress) -> Unit = {},
    ): ProfileAnalysisReport {
        require(userId > 0) { "userId 必须是正数" }
        require(maxBatches > 0) { "maxBatches 必须是正数" }
        check(PluginConfig.profileEnabled) { "历史用户画像分析未启用" }
        check(UserProfileStore.isAvailable) { "用户画像数据库不可用" }

        if (!runningUsers.add(userId)) {
            return ProfileAnalysisReport(
                userId = userId,
                processedBatches = 0,
                processedMessages = 0,
                appliedOperations = 0,
                skippedOperations = 0,
                usage = ProfileTokenUsage(),
                profile = UserProfileStore.load(userId),
                caughtUp = false,
                alreadyRunning = true,
            )
        }

        try {
            return userLocks.withUserLocks(listOf(userId)) {
                analyzeExclusive(userId, maxBatches, runToken, onProgress)
            }
        } finally {
            runningUsers.remove(userId)
        }
    }

    suspend fun compact(
        userId: Long,
        runToken: ProfileAnalysisRunToken = newRunToken(),
    ): ProfileCompactionReport {
        require(userId > 0) { "userId 必须是正数" }
        check(PluginConfig.profileEnabled) { "历史用户画像分析未启用" }
        check(UserProfileStore.isAvailable) { "用户画像数据库不可用" }

        if (!runningCompactions.add(userId)) {
            val profile = withContext(Dispatchers.IO) { UserProfileStore.load(userId) }
                ?: throw IllegalArgumentException("用户 $userId 尚无画像")
            return unchangedCompactionReport(profile, alreadyRunning = true)
        }

        try {
            return userLocks.withUserLocks(listOf(userId)) locked@{
                val profile = withContext(Dispatchers.IO) { UserProfileStore.load(userId) }
                    ?: throw IllegalArgumentException("用户 $userId 尚无画像")
                if (!runGate.canContinue(runToken)) {
                    return@locked unchangedCompactionReport(profile, stopped = true)
                }
                if (profile.items.isEmpty()) {
                    return@locked unchangedCompactionReport(profile)
                }

                val endpoint = checkNotNull(LargeLanguageModels.profile) { "画像分析模型未配置" }
                val model: ProfileCompactionModel = ProfileModelClient(endpoint)
                val supportStats = withContext(Dispatchers.IO) { UserProfileStore.loadSupportStats(userId) }
                val (result, plan) = compactWithRetry(model, profile, supportStats)
                if (plan.reduction.profile.version != profile.version) {
                    val batch = compactionBatch(profile, result.rawResponse)
                    withContext(Dispatchers.IO) {
                        UserProfileStore.commitCompaction(plan, batch, result.usage)
                    }
                    ProfileOperationLogger.log(
                        context = "source=COMPACTION user=$userId",
                        reductions = listOf(plan.reduction),
                    )
                }
                ProfileCompactionReport(
                    userId = userId,
                    beforeItems = profile.items.size,
                    afterItems = plan.reduction.profile.items.size,
                    mergedGroups = plan.mergedGroups,
                    rewrittenItems = plan.rewrittenItems,
                    deletedItems = plan.deletedItems,
                    repairedItemRanges = plan.repairedItemRanges,
                    summaryChanged = plan.reduction.profile.summary != profile.summary,
                    skippedOperations = plan.skippedOperations.size,
                    usage = result.usage,
                    profile = plan.reduction.profile,
                )
            }
        } finally {
            runningCompactions.remove(userId)
        }
    }

    suspend fun analyzeGroup(
        groupId: Long,
        maxBatches: Int,
        runToken: ProfileAnalysisRunToken = newRunToken(),
        onProgress: suspend (GroupProfileAnalysisProgress) -> Unit = {},
    ): GroupProfileAnalysisReport {
        require(groupId > 0) { "groupId 必须是正数" }
        require(maxBatches > 0) { "maxBatches 必须是正数" }
        check(PluginConfig.profileEnabled) { "历史用户画像分析未启用" }
        check(UserProfileStore.isAvailable) { "用户画像数据库不可用" }

        if (!runningGroups.add(groupId)) {
            return GroupProfileAnalysisReport(
                botId = null,
                groupId = groupId,
                processedBatches = 0,
                processedMessages = 0,
                analyzedUsers = 0,
                appliedOperations = 0,
                skippedOperations = 0,
                usage = ProfileTokenUsage(),
                cursorTime = 0,
                snapshotEndTime = 0,
                caughtUp = false,
                alreadyRunning = true,
            )
        }

        try {
            return analyzeGroupExclusive(groupId, maxBatches, runToken, onProgress)
        } finally {
            runningGroups.remove(groupId)
        }
    }

    private suspend fun analyzeExclusive(
        userId: Long,
        maxBatches: Int,
        runToken: ProfileAnalysisRunToken,
        onProgress: suspend (ProfileAnalysisProgress) -> Unit,
    ): ProfileAnalysisReport {
        val endpoint = checkNotNull(LargeLanguageModels.profile) {
            "画像分析模型未配置，请设置 profileModelApi/profileModelToken，或配置可继承的聊天模型接入点"
        }
        val model: ProfileModel = ProfileModelClient(endpoint)
        val historyFile = resolveHistoryFile()
        val reader = withContext(Dispatchers.IO) { ProfileHistoryReader(historyFile) }
        val bounds = withContext(Dispatchers.IO) { reader.findUserTimeBounds(userId) }
            ?: return emptyReport(userId)

        var profile = withContext(Dispatchers.IO) { UserProfileStore.load(userId) }
            ?: UserProfileSnapshot(
                userId = userId,
                cursorTime = bounds.startTime,
                snapshotEndTime = bounds.endTime,
            )
        if (profile.cursorTime >= profile.snapshotEndTime && bounds.endTime > profile.snapshotEndTime) {
            profile = profile.copy(snapshotEndTime = bounds.endTime)
        }

        var processedBatches = 0
        var processedMessages = 0
        var appliedOperations = 0
        var skippedOperations = 0
        var totalUsage = ProfileTokenUsage()
        var caughtUp = false

        while (processedBatches < maxBatches && runGate.canContinue(runToken)) {
            val batch = withContext(Dispatchers.IO) {
                reader.loadNextBatch(
                    userId = userId,
                    startTime = profile.cursorTime,
                    snapshotEndTime = profile.snapshotEndTime,
                    targetMessageLimit = PluginConfig.profileBatchTargetMessages.coerceAtLeast(1),
                    maxEpisodes = PluginConfig.profileBatchMaxEpisodes.coerceAtLeast(1),
                    episodeGapSeconds = PluginConfig.profileEpisodeGapMinutes.coerceAtLeast(0) * 60,
                    contextBeforeMessages = PluginConfig.profileContextBeforeMessages.coerceAtLeast(0),
                    contextAfterMessages = PluginConfig.profileContextAfterMessages.coerceAtLeast(0),
                    contextCoreMessages = PluginConfig.profileContextCoreMessages.coerceAtLeast(1),
                    maxMessageChars = PluginConfig.profileMaxMessageChars.coerceAtLeast(80),
                )
            }
            if (batch == null) {
                caughtUp = true
                break
            }

            val supportStats = withContext(Dispatchers.IO) {
                UserProfileStore.loadSupportStats(userId)
            }
            val (result, reduction) = analyzeWithRetry(model, profile, batch, supportStats)
            withContext(Dispatchers.IO) {
                UserProfileStore.commit(
                    reduction = reduction,
                    batch = batch,
                    usage = result.usage,
                    source = ProfileRevisionSource.BACKFILL,
                )
            }
            ProfileOperationLogger.log(
                context = "source=BACKFILL batch=[${batch.startTime},${batch.endTime})",
                reductions = listOf(reduction),
            )
            profile = reduction.profile
            processedBatches++
            processedMessages += batch.messages.size
            appliedOperations += reduction.operations.size
            skippedOperations += reduction.skippedOperations.size
            totalUsage += result.usage
            onProgress(
                ProfileAnalysisProgress(
                    batchIndex = processedBatches,
                    startTime = batch.startTime,
                    endTime = batch.endTime,
                    messageCount = batch.messages.size,
                    operationCount = reduction.operations.size,
                    skippedOperationCount = reduction.skippedOperations.size,
                    usage = result.usage,
                )
            )
        }

        if (!caughtUp && profile.cursorTime >= profile.snapshotEndTime) caughtUp = true
        val stopped = processedBatches < maxBatches && !caughtUp && !runGate.canContinue(runToken)
        return ProfileAnalysisReport(
            userId = userId,
            processedBatches = processedBatches,
            processedMessages = processedMessages,
            appliedOperations = appliedOperations,
            skippedOperations = skippedOperations,
            usage = totalUsage,
            profile = withContext(Dispatchers.IO) { UserProfileStore.load(userId) } ?: profile,
            caughtUp = caughtUp,
            stopped = stopped,
        )
    }

    private suspend fun analyzeGroupExclusive(
        groupId: Long,
        maxBatches: Int,
        runToken: ProfileAnalysisRunToken,
        onProgress: suspend (GroupProfileAnalysisProgress) -> Unit,
    ): GroupProfileAnalysisReport {
        val reader = withContext(Dispatchers.IO) { ProfileHistoryReader(resolveHistoryFile()) }
        val bounds = withContext(Dispatchers.IO) { reader.findGroupTimeBounds(groupId) }
            ?: return emptyGroupReport(groupId)
        var cursor = withContext(Dispatchers.IO) {
            UserProfileStore.loadGroupCursor(bounds.botId, groupId)
        } ?: GroupProfileCursor(
            botId = bounds.botId,
            groupId = groupId,
            cursorTime = bounds.startTime,
            snapshotEndTime = bounds.endTime,
        )
        if (cursor.cursorTime >= cursor.snapshotEndTime && bounds.endTime > cursor.snapshotEndTime) {
            cursor = cursor.copy(snapshotEndTime = bounds.endTime)
        }

        var processedBatches = 0
        var processedMessages = 0
        var analyzedUsers = 0
        var appliedOperations = 0
        var skippedOperations = 0
        var totalUsage = ProfileTokenUsage()
        var caughtUp = cursor.cursorTime >= cursor.snapshotEndTime
        val model: ConversationProfileModel by lazy {
            val endpoint = checkNotNull(LargeLanguageModels.profile) {
                "画像分析模型未配置，请设置 profileModelApi/profileModelToken，或配置可继承的聊天模型接入点"
            }
            ProfileModelClient(endpoint)
        }

        while (processedBatches < maxBatches && !caughtUp && runGate.canContinue(runToken)) {
            val batch = withContext(Dispatchers.IO) {
                reader.loadNextConversationBatch(
                    botId = cursor.botId,
                    groupId = groupId,
                    startTime = cursor.cursorTime,
                    snapshotEndTime = cursor.snapshotEndTime,
                    messageLimit = PluginConfig.profileAutoConversationMessageLimit.coerceAtLeast(1),
                    maxMessageChars = PluginConfig.profileMaxMessageChars.coerceAtLeast(80),
                )
            }
            if (batch == null) {
                cursor = cursor.copy(
                    cursorTime = cursor.snapshotEndTime,
                    updatedAt = System.currentTimeMillis(),
                )
                withContext(Dispatchers.IO) { UserProfileStore.saveGroupCursor(cursor) }
                caughtUp = true
                break
            }

            val report = try {
                analyzeConversationBatch(
                    batch = batch,
                    minAuthoredTextChars = PluginConfig.profileAutoMinAuthoredTextChars,
                    model = model,
                    retryMax = PluginConfig.profileRetryMax,
                    summaryMaxLength = PluginConfig.profileSummaryMaxLength,
                    onRetryFailure = { message, cause -> JChatGPT.logger.warning(message, cause) },
                    onCommittedOperations = ProfileOperationLogger::log,
                )
            } catch (cause: ModelSafetyRejectionException) {
                JChatGPT.logger.warning(
                    "群 $groupId 会话画像 [${batch.startTime}, ${batch.endTime}) 被模型安全策略拒绝，" +
                            "已跳过该批并继续后续历史 code=${cause.errorCode ?: "unknown"}"
                )
                null
            }
            cursor = cursor.copy(
                cursorTime = batch.endTime,
                updatedAt = System.currentTimeMillis(),
            )
            withContext(Dispatchers.IO) { UserProfileStore.saveGroupCursor(cursor) }

            val usage = report?.usage ?: ProfileTokenUsage()
            processedBatches++
            processedMessages += batch.messages.size
            analyzedUsers += report?.analyzedUsers ?: 0
            appliedOperations += report?.appliedOperations ?: 0
            skippedOperations += report?.skippedOperations ?: 0
            totalUsage += usage
            caughtUp = cursor.cursorTime >= cursor.snapshotEndTime
            onProgress(
                GroupProfileAnalysisProgress(
                    batchIndex = processedBatches,
                    startTime = batch.startTime,
                    endTime = batch.endTime,
                    messageCount = batch.messages.size,
                    analyzedUsers = report?.analyzedUsers ?: 0,
                    appliedOperations = report?.appliedOperations ?: 0,
                    skippedOperations = report?.skippedOperations ?: 0,
                    usage = usage,
                )
            )
        }

        return GroupProfileAnalysisReport(
            botId = cursor.botId,
            groupId = groupId,
            processedBatches = processedBatches,
            processedMessages = processedMessages,
            analyzedUsers = analyzedUsers,
            appliedOperations = appliedOperations,
            skippedOperations = skippedOperations,
            usage = totalUsage,
            cursorTime = cursor.cursorTime,
            snapshotEndTime = cursor.snapshotEndTime,
            caughtUp = caughtUp,
            stopped = processedBatches < maxBatches && !caughtUp && !runGate.canContinue(runToken),
        )
    }

    suspend fun analyzeConversation(
        botId: Long,
        groupId: Long,
        startTime: Int,
        endTime: Int,
        minAuthoredTextChars: Int,
    ): ConversationProfileAnalysisReport? {
        require(startTime < endTime) { "startTime must be before endTime" }
        check(PluginConfig.profileEnabled) { "历史用户画像分析未启用" }
        check(UserProfileStore.isAvailable) { "用户画像数据库不可用" }

        val endpoint = checkNotNull(LargeLanguageModels.profile) { "画像分析模型未配置" }
        val model: ConversationProfileModel = ProfileModelClient(endpoint)
        val reader = withContext(Dispatchers.IO) { ProfileHistoryReader(resolveLiveHistoryFile()) }
        val batch = withContext(Dispatchers.IO) {
            reader.loadLatestConversationBatch(
                botId = botId,
                groupId = groupId,
                startTime = startTime,
                endTime = endTime,
                maxMessageChars = PluginConfig.profileMaxMessageChars.coerceAtLeast(80),
            )
        } ?: return null
        return analyzeConversationBatch(
            batch = batch,
            minAuthoredTextChars = minAuthoredTextChars,
            model = model,
            retryMax = PluginConfig.profileRetryMax,
            summaryMaxLength = PluginConfig.profileSummaryMaxLength,
            onRetryFailure = { message, cause -> JChatGPT.logger.warning(message, cause) },
            onCommittedOperations = ProfileOperationLogger::log,
        )
    }

    internal suspend fun analyzeConversationBatch(
        batch: ConversationProfileBatch,
        minAuthoredTextChars: Int,
        model: ConversationProfileModel,
        retryMax: Int,
        summaryMaxLength: Int,
        onRetryFailure: (String, Throwable) -> Unit = { _, _ -> },
        onCommittedOperations: (String, Collection<ProfileReduction>) -> Unit = { _, _ -> },
    ): ConversationProfileAnalysisReport? {
        check(UserProfileStore.isAvailable) { "用户画像数据库不可用" }
        val eligibleUserIds = batch.authoredTextCharsByUser
            .filterValues { it >= minAuthoredTextChars.coerceAtLeast(1) }
            .keys
        if (eligibleUserIds.isEmpty()) return null
        val profileState = userLocks.withUserLocks(eligibleUserIds) {
            withContext(Dispatchers.IO) {
                if (UserProfileStore.isConversationProcessed(batch.inputHash)) null
                else {
                    val profiles = loadConversationProfiles(eligibleUserIds)
                    profiles to loadConversationSupportStats(eligibleUserIds)
                }
            }
        } ?: return null
        val (profiles, supportStatsByUserId) = profileState
        val (result, reductions) = analyzeConversationWithRetry(
            model = model,
            profiles = profiles,
            batch = batch,
            eligibleUserIds = eligibleUserIds,
            supportStatsByUserId = supportStatsByUserId,
            retryMax = retryMax,
            summaryMaxLength = summaryMaxLength,
            onRetryFailure = onRetryFailure,
        )
        val totalUsage = result.usage
        val commitOutcome = userLocks.withUserLocks(eligibleUserIds) {
            withContext(Dispatchers.IO) {
                if (UserProfileStore.isConversationProcessed(batch.inputHash)) {
                    ConversationCommitOutcome.AlreadyProcessed
                } else {
                    val latestProfiles = loadConversationProfiles(eligibleUserIds)
                    val committedReductions = if (hasProfileVersionConflict(profiles, latestProfiles)) {
                        ConversationProfileReducer.reduceRebased(
                            expectedProfiles = profiles,
                            latestProfiles = latestProfiles,
                            batch = batch,
                            eligibleUserIds = eligibleUserIds,
                            response = result.response,
                            model = model.modelName,
                            promptVersion = ProfilePromptStore.PROMPT_VERSION,
                            summaryMaxLength = summaryMaxLength.coerceAtLeast(100),
                        )
                    } else {
                        reductions
                    }
                    UserProfileStore.commitConversation(
                        reductions = committedReductions.map { reduction ->
                            reduction to batch.forUser(reduction.profile.userId)
                        },
                        usage = totalUsage,
                    )
                    ConversationCommitOutcome.Committed(committedReductions)
                }
            }
        }

        val committedReductions = when (commitOutcome) {
            ConversationCommitOutcome.AlreadyProcessed -> return null
            is ConversationCommitOutcome.Committed -> commitOutcome.reductions
        }

        onCommittedOperations(
            "source=CONVERSATION bot=${batch.botId} group=${batch.groupId} " +
                    "batch=[${batch.startTime},${batch.endTime})",
            committedReductions,
        )
        return ConversationProfileAnalysisReport(
            analyzedUsers = eligibleUserIds.size,
            processedMessages = batch.messages.size,
            appliedOperations = committedReductions.sumOf { it.operations.size },
            skippedOperations = committedReductions.sumOf { it.skippedOperations.size },
            usage = totalUsage,
        )
    }

    private fun loadConversationProfiles(userIds: Set<Long>): Map<Long, UserProfileSnapshot> =
        userIds.associateWith { userId ->
            UserProfileStore.load(userId) ?: UserProfileSnapshot(
                userId = userId,
                cursorTime = 0,
                snapshotEndTime = 0,
            )
        }

    private fun loadConversationSupportStats(
        userIds: Set<Long>,
    ): Map<Long, Map<String, ProfileItemSupportStats>> = userIds.associateWith { userId ->
        UserProfileStore.loadSupportStats(userId)
    }

    private fun hasProfileVersionConflict(
        expectedProfiles: Map<Long, UserProfileSnapshot>,
        latestProfiles: Map<Long, UserProfileSnapshot>,
    ): Boolean = expectedProfiles.any { (userId, expected) ->
        latestProfiles[userId]?.version != expected.version
    }

    private suspend fun analyzeConversationWithRetry(
        model: ConversationProfileModel,
        profiles: Map<Long, UserProfileSnapshot>,
        batch: ConversationProfileBatch,
        eligibleUserIds: Set<Long>,
        supportStatsByUserId: Map<Long, Map<String, ProfileItemSupportStats>>,
        retryMax: Int,
        summaryMaxLength: Int,
        onRetryFailure: (String, Throwable) -> Unit,
    ): Pair<ConversationProfileModelResult, List<ProfileReduction>> {
        val attempts = retryMax.coerceIn(0, 3) + 1
        val retryBackoff = RetryBackoff.fromConfig()
        var lastFailure: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                val result = model.analyzeConversation(
                    profiles,
                    batch,
                    eligibleUserIds,
                    supportStatsByUserId,
                )
                val reductions = ConversationProfileReducer.reduce(
                    profiles = profiles,
                    batch = batch,
                    eligibleUserIds = eligibleUserIds,
                    response = result.response,
                    model = model.modelName,
                    promptVersion = ProfilePromptStore.PROMPT_VERSION,
                    summaryMaxLength = summaryMaxLength.coerceAtLeast(100),
                )
                return result to reductions
            } catch (cause: Exception) {
                if (cause is CancellationException) throw cause
                if (cause is ModelRequestRejectedException) {
                    onRetryFailure(
                        "群 ${batch.groupId} 会话画像 [${batch.startTime}, ${batch.endTime}) " +
                                "被模型拒绝，已停止重试",
                        cause,
                    )
                    throw cause
                }
                lastFailure = cause
                handleRetryFailure(
                    attempt = attempt,
                    attempts = attempts,
                    message = "群 ${batch.groupId} 会话画像 [${batch.startTime}, ${batch.endTime}) " +
                            "第 ${attempt + 1}/$attempts 次分析失败",
                    cause = cause,
                    retryBackoff = retryBackoff,
                    logFailure = onRetryFailure,
                )
            }
        }
        throw IllegalStateException(
            "会话画像 [${batch.startTime}, ${batch.endTime}) 连续 $attempts 次分析失败，未提交任何结果",
            lastFailure,
        )
    }

    private suspend fun analyzeWithRetry(
        model: ProfileModel,
        profile: UserProfileSnapshot,
        batch: ProfileHistoryBatch,
        supportStats: Map<String, ProfileItemSupportStats>,
        advanceBackfillCursor: Boolean = true,
    ): Pair<ProfileModelResult, ProfileReduction> {
        val attempts = PluginConfig.profileRetryMax.coerceIn(0, 3) + 1
        val retryBackoff = RetryBackoff.fromConfig()
        var lastFailure: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                val result = model.analyze(profile, batch, supportStats)
                val reduction = UserProfileReducer.reduce(
                    current = profile,
                    batch = batch,
                    response = result.response,
                    model = model.modelName,
                    promptVersion = ProfilePromptStore.PROMPT_VERSION,
                    summaryMaxLength = PluginConfig.profileSummaryMaxLength.coerceAtLeast(100),
                    advanceBackfillCursor = advanceBackfillCursor,
                )
                logSkippedOperations(
                    "用户 ${batch.userId} 画像批次 [${batch.startTime}, ${batch.endTime})",
                    reduction.skippedOperations,
                )
                return result to reduction
            } catch (cause: Exception) {
                if (cause is CancellationException) throw cause
                if (cause is ModelRequestRejectedException) {
                    JChatGPT.logger.warning(
                        "用户 ${batch.userId} 画像批次 [${batch.startTime}, ${batch.endTime}) " +
                                "被模型拒绝，已停止重试",
                        cause,
                    )
                    throw cause
                }
                lastFailure = cause
                handleRetryFailure(
                    attempt = attempt,
                    attempts = attempts,
                    message = "用户 ${batch.userId} 画像批次 [${batch.startTime}, ${batch.endTime}) " +
                            "第 ${attempt + 1}/$attempts 次分析失败",
                    cause = cause,
                    retryBackoff = retryBackoff,
                    logFailure = JChatGPT.logger::warning,
                )
            }
        }
        throw IllegalStateException(
            "画像批次 [${batch.startTime}, ${batch.endTime}) 连续 $attempts 次分析失败，水位线未推进",
            lastFailure,
        )
    }

    private suspend fun compactWithRetry(
        model: ProfileCompactionModel,
        profile: UserProfileSnapshot,
        supportStats: Map<String, ProfileItemSupportStats>,
    ): Pair<ProfileCompactionModelResult, ProfileCompactionPlan> {
        val attempts = PluginConfig.profileRetryMax.coerceIn(0, 3) + 1
        val retryBackoff = RetryBackoff.fromConfig()
        var lastFailure: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                val result = model.compact(profile, supportStats)
                val plan = UserProfileCompactor.reduce(
                    current = profile,
                    supportStats = supportStats,
                    response = result.response,
                    model = model.modelName,
                    promptVersion = ProfilePromptStore.COMPACTION_PROMPT_VERSION,
                    summaryMaxLength = PluginConfig.profileSummaryMaxLength.coerceAtLeast(100),
                )
                return result to plan
            } catch (cause: Exception) {
                if (cause is CancellationException) throw cause
                if (cause is ModelRequestRejectedException) {
                    JChatGPT.logger.warning(
                        "用户 ${profile.userId} 画像压缩被模型拒绝，已停止重试",
                        cause,
                    )
                    throw cause
                }
                lastFailure = cause
                handleRetryFailure(
                    attempt = attempt,
                    attempts = attempts,
                    message = "用户 ${profile.userId} 画像压缩第 ${attempt + 1}/$attempts 次失败",
                    cause = cause,
                    retryBackoff = retryBackoff,
                    logFailure = JChatGPT.logger::warning,
                )
            }
        }
        throw IllegalStateException(
            "用户 ${profile.userId} 画像压缩连续 $attempts 次失败，未提交任何结果",
            lastFailure,
        )
    }

    private suspend fun handleRetryFailure(
        attempt: Int,
        attempts: Int,
        message: String,
        cause: Throwable,
        retryBackoff: RetryBackoff,
        logFailure: (String, Throwable) -> Unit,
    ) {
        if (attempt + 1 >= attempts) {
            logFailure("$message，已无剩余尝试", cause)
            return
        }
        val retryDelayMillis = retryBackoff.delayMillis(attempt + 1)
        logFailure("$message，将在 ${retryDelayMillis}ms 后重试", cause)
        if (retryDelayMillis > 0) delay(retryDelayMillis)
    }

    private fun compactionBatch(profile: UserProfileSnapshot, rawResponse: String): ProfileHistoryBatch {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${profile.userId}|${profile.version}|$rawResponse".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val startTime = profile.items.minOfOrNull(UserProfileItem::firstSeenAt) ?: profile.cursorTime
        val lastConfirmedAt = profile.items.maxOfOrNull(UserProfileItem::lastConfirmedAt) ?: startTime
        val endTime = if (lastConfirmedAt == Int.MAX_VALUE) lastConfirmedAt else lastConfirmedAt + 1
        return ProfileHistoryBatch(
            userId = profile.userId,
            startTime = startTime,
            endTime = endTime,
            messages = emptyList(),
            aliases = mapOf(profile.userId to "TARGET"),
            inputHash = "compact-$digest",
        )
    }

    private fun resolveHistoryFile(): File {
        val configured = PluginConfig.profileHistoryDatabasePath.trim()
        return if (configured.isNotEmpty()) {
            File(configured).absoluteFile
        } else {
            checkNotNull(ChatHistoryStore.databaseFileOrNull) { "聊天记录数据库不可用" }
        }
    }

    private fun resolveLiveHistoryFile(): File =
        checkNotNull(ChatHistoryStore.databaseFileOrNull) { "聊天记录数据库不可用" }

    private fun emptyReport(userId: Long) = ProfileAnalysisReport(
        userId = userId,
        processedBatches = 0,
        processedMessages = 0,
        appliedOperations = 0,
        skippedOperations = 0,
        usage = ProfileTokenUsage(),
        profile = null,
        caughtUp = true,
    )

    private fun emptyGroupReport(groupId: Long) = GroupProfileAnalysisReport(
        botId = null,
        groupId = groupId,
        processedBatches = 0,
        processedMessages = 0,
        analyzedUsers = 0,
        appliedOperations = 0,
        skippedOperations = 0,
        usage = ProfileTokenUsage(),
        cursorTime = 0,
        snapshotEndTime = 0,
        caughtUp = true,
    )

    private fun unchangedCompactionReport(
        profile: UserProfileSnapshot,
        alreadyRunning: Boolean = false,
        stopped: Boolean = false,
    ) = ProfileCompactionReport(
        userId = profile.userId,
        beforeItems = profile.items.size,
        afterItems = profile.items.size,
        mergedGroups = 0,
        rewrittenItems = 0,
        deletedItems = 0,
        repairedItemRanges = 0,
        summaryChanged = false,
        skippedOperations = 0,
        usage = ProfileTokenUsage(),
        profile = profile,
        alreadyRunning = alreadyRunning,
        stopped = stopped,
    )

    private operator fun ProfileTokenUsage.plus(other: ProfileTokenUsage) = ProfileTokenUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
    )

    private fun logSkippedOperations(context: String, skipped: List<String>) {
        if (skipped.isEmpty()) return
        JChatGPT.logger.warning(
            "$context 跳过 ${skipped.size} 项无效建议：" + skipped.take(8).joinToString("；") +
                    if (skipped.size > 8) "；其余 ${skipped.size - 8} 项已省略" else ""
        )
    }

    private sealed class ConversationCommitOutcome {
        data class Committed(
            val reductions: List<ProfileReduction>,
        ) : ConversationCommitOutcome()
        object AlreadyProcessed : ConversationCommitOutcome()
    }
}

internal fun isGroupAnalysisPending(
    bounds: ProfileHistoryReader.GroupTimeBounds,
    cursor: GroupProfileCursor?,
): Boolean = pendingGroupAnalysisRange(bounds, cursor) != null

internal fun pendingGroupAnalysisRange(
    bounds: ProfileHistoryReader.GroupTimeBounds,
    cursor: GroupProfileCursor?,
): ProfileHistoryReader.GroupTimeBounds? {
    val startTime = cursor?.cursorTime ?: bounds.startTime
    val endTime = maxOf(cursor?.snapshotEndTime ?: bounds.endTime, bounds.endTime)
    return bounds.copy(startTime = startTime, endTime = endTime)
        .takeIf { startTime < endTime }
}
