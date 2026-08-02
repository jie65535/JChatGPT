package top.jie65535.mirai.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.config.PluginConfig
import top.jie65535.mirai.data.ChatHistoryStore
import top.jie65535.mirai.llm.LargeLanguageModels
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object UserProfileAnalysisService {
    private val runningUsers = ConcurrentHashMap.newKeySet<Long>()
    private val concurrencyLimiter = Semaphore(1)

    suspend fun analyze(
        userId: Long,
        maxBatches: Int,
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
                usage = ProfileTokenUsage(),
                profile = UserProfileStore.load(userId),
                caughtUp = false,
                alreadyRunning = true,
            )
        }

        try {
            return concurrencyLimiter.withPermit {
                analyzeExclusive(userId, maxBatches, onProgress)
            }
        } finally {
            runningUsers.remove(userId)
        }
    }

    private suspend fun analyzeExclusive(
        userId: Long,
        maxBatches: Int,
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
        var totalUsage = ProfileTokenUsage()
        var caughtUp = false

        while (processedBatches < maxBatches) {
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

            val (result, reduction) = analyzeWithRetry(model, profile, batch)
            withContext(Dispatchers.IO) {
                UserProfileStore.commit(
                    reduction = reduction,
                    batch = batch,
                    usage = result.usage,
                    source = ProfileRevisionSource.BACKFILL,
                )
            }
            profile = reduction.profile
            processedBatches++
            processedMessages += batch.messages.size
            appliedOperations += reduction.operations.size
            totalUsage += result.usage
            onProgress(
                ProfileAnalysisProgress(
                    batchIndex = processedBatches,
                    startTime = batch.startTime,
                    endTime = batch.endTime,
                    messageCount = batch.messages.size,
                    operationCount = reduction.operations.size,
                    usage = result.usage,
                )
            )
        }

        if (!caughtUp && profile.cursorTime >= profile.snapshotEndTime) caughtUp = true
        return ProfileAnalysisReport(
            userId = userId,
            processedBatches = processedBatches,
            processedMessages = processedMessages,
            appliedOperations = appliedOperations,
            usage = totalUsage,
            profile = withContext(Dispatchers.IO) { UserProfileStore.load(userId) } ?: profile,
            caughtUp = caughtUp,
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

        return concurrencyLimiter.withPermit {
            val endpoint = checkNotNull(LargeLanguageModels.profile) { "画像分析模型未配置" }
            val model: ConversationProfileModel = ProfileModelClient(endpoint)
            val reader = withContext(Dispatchers.IO) { ProfileHistoryReader(resolveLiveHistoryFile()) }
            val batch = withContext(Dispatchers.IO) {
                reader.loadConversationBatch(
                    botId = botId,
                    groupId = groupId,
                    startTime = startTime,
                    endTime = endTime,
                    messageLimit = PluginConfig.profileAutoConversationMessageLimit.coerceIn(20, 500),
                    maxMessageChars = PluginConfig.profileMaxMessageChars.coerceAtLeast(80),
                )
            } ?: return@withPermit null
            val eligibleUserIds = batch.authoredTextCharsByUser
                .filterValues { it >= minAuthoredTextChars.coerceAtLeast(1) }
                .keys
            if (eligibleUserIds.isEmpty()) return@withPermit null
            if (withContext(Dispatchers.IO) { UserProfileStore.isConversationProcessed(batch.inputHash) }) {
                return@withPermit null
            }
            val profiles = withContext(Dispatchers.IO) {
                eligibleUserIds.associateWith { userId ->
                    UserProfileStore.load(userId) ?: UserProfileSnapshot(
                        userId = userId,
                        cursorTime = 0,
                        snapshotEndTime = 0,
                    )
                }
            }

            val (result, reductions) = analyzeConversationWithRetry(
                model = model,
                profiles = profiles,
                batch = batch,
                eligibleUserIds = eligibleUserIds,
            )
            withContext(Dispatchers.IO) {
                UserProfileStore.commitConversation(
                    reductions = reductions.map { reduction -> reduction to batch.forUser(reduction.profile.userId) },
                    usage = result.usage,
                )
            }
            ConversationProfileAnalysisReport(
                analyzedUsers = eligibleUserIds.size,
                processedMessages = batch.messages.size,
                appliedOperations = reductions.sumOf { it.operations.size },
                usage = result.usage,
            )
        }
    }

    private suspend fun analyzeConversationWithRetry(
        model: ConversationProfileModel,
        profiles: Map<Long, UserProfileSnapshot>,
        batch: ConversationProfileBatch,
        eligibleUserIds: Set<Long>,
    ): Pair<ConversationProfileModelResult, List<ProfileReduction>> {
        val attempts = PluginConfig.profileRetryMax.coerceIn(0, 3) + 1
        var lastFailure: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                val result = model.analyzeConversation(profiles, batch, eligibleUserIds)
                val reductions = ConversationProfileReducer.reduce(
                    profiles = profiles,
                    batch = batch,
                    eligibleUserIds = eligibleUserIds,
                    response = result.response,
                    model = model.modelName,
                    promptVersion = ProfilePromptStore.PROMPT_VERSION,
                    summaryMaxLength = PluginConfig.profileSummaryMaxLength.coerceAtLeast(100),
                )
                return result to reductions
            } catch (cause: Exception) {
                if (cause is CancellationException) throw cause
                lastFailure = cause
                JChatGPT.logger.warning(
                    "群 ${batch.groupId} 会话画像 [${batch.startTime}, ${batch.endTime}) " +
                            "第 ${attempt + 1}/$attempts 次分析失败",
                    cause,
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
        advanceBackfillCursor: Boolean = true,
    ): Pair<ProfileModelResult, ProfileReduction> {
        val attempts = PluginConfig.profileRetryMax.coerceIn(0, 3) + 1
        var lastFailure: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                val result = model.analyze(profile, batch)
                val reduction = UserProfileReducer.reduce(
                    current = profile,
                    batch = batch,
                    response = result.response,
                    model = model.modelName,
                    promptVersion = ProfilePromptStore.PROMPT_VERSION,
                    summaryMaxLength = PluginConfig.profileSummaryMaxLength.coerceAtLeast(100),
                    advanceBackfillCursor = advanceBackfillCursor,
                )
                return result to reduction
            } catch (cause: Exception) {
                if (cause is CancellationException) throw cause
                lastFailure = cause
                JChatGPT.logger.warning(
                    "用户 ${batch.userId} 画像批次 [${batch.startTime}, ${batch.endTime}) " +
                            "第 ${attempt + 1}/$attempts 次分析失败",
                    cause,
                )
            }
        }
        throw IllegalStateException(
            "画像批次 [${batch.startTime}, ${batch.endTime}) 连续 $attempts 次分析失败，水位线未推进",
            lastFailure,
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
        usage = ProfileTokenUsage(),
        profile = null,
        caughtUp = true,
    )

    private operator fun ProfileTokenUsage.plus(other: ProfileTokenUsage) = ProfileTokenUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
    )
}
