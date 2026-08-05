package top.jie65535.mirai.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.config.PluginConfig
import top.jie65535.mirai.llm.LargeLanguageModels
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField
import java.util.Locale

object ProfileDailyMaintenance {
    private val lock = Any()
    private var schedulerJob: Job? = null

    fun reload() {
        synchronized(lock) {
            schedulerJob?.cancel()
            schedulerJob = null
            if (!PluginConfig.profileEnabled || !PluginConfig.profileDailyGroupUpdateEnabled) return@synchronized
            if (!UserProfileStore.isAvailable) {
                JChatGPT.logger.warning("用户画像数据库不可用，每日群画像推进未启动")
                return@synchronized
            }

            val scheduledTime = parseProfileDailyUpdateTime(PluginConfig.profileDailyGroupUpdateTime)
            if (scheduledTime == null) {
                JChatGPT.logger.warning(
                    "每日群画像推进时间无效: '${PluginConfig.profileDailyGroupUpdateTime}'，请使用 HH:mm 格式"
                )
                return@synchronized
            }
            val maxPendingAgeDays = PluginConfig.profileDailyGroupUpdateMaxPendingAgeDays
            if (maxPendingAgeDays <= 0) {
                JChatGPT.logger.warning("每日群画像最大积压天数必须为正数，当前值: $maxPendingAgeDays")
                return@synchronized
            }

            val zoneId = ZoneId.systemDefault()
            schedulerJob = JChatGPT.launch {
                scheduleLoop(scheduledTime, zoneId, maxPendingAgeDays)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            schedulerJob?.cancel()
            schedulerJob = null
        }
    }

    private suspend fun scheduleLoop(
        scheduledTime: LocalTime,
        zoneId: ZoneId,
        maxPendingAgeDays: Int,
    ) {
        while (currentCoroutineContext().isActive) {
            val now = ZonedDateTime.now(zoneId)
            val nextRun = nextProfileDailyUpdateAt(now, scheduledTime)
            val delayMillis = Duration.between(now.toInstant(), nextRun.toInstant()).toMillis().coerceAtLeast(1L)
            JChatGPT.logger.info(
                "每日群画像推进已计划: next=$nextRun maxPendingAgeDays=$maxPendingAgeDays"
            )
            delay(delayMillis)
            try {
                runOnce(maxPendingAgeDays)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                JChatGPT.logger.error("每日群画像推进失败，将在下一计划时间重试", cause)
            }
        }
    }

    private suspend fun runOnce(maxPendingAgeDays: Int) {
        if (!PluginConfig.profileEnabled || !PluginConfig.profileDailyGroupUpdateEnabled) return
        if (!UserProfileStore.isAvailable) {
            JChatGPT.logger.warning("用户画像数据库不可用，跳过本次每日群画像推进")
            return
        }
        if (LargeLanguageModels.profile == null) {
            JChatGPT.logger.warning("画像分析模型未配置，跳过本次每日群画像推进")
            return
        }

        val oldestAllowedTime = oldestAllowedProfilePendingTime(Instant.now().epochSecond, maxPendingAgeDays)
        val groupIds = UserProfileAnalysisService.listRecentPendingHistoryGroupIds(oldestAllowedTime)
        if (groupIds.isEmpty()) {
            JChatGPT.logger.info("每日群画像推进无需运行：没有符合 $maxPendingAgeDays 天积压门槛的群")
            return
        }

        JChatGPT.logger.info("每日群画像推进开始: groups=${groupIds.size} maxPendingAgeDays=$maxPendingAgeDays")
        val runToken = UserProfileAnalysisService.newRunToken()
        val outcomes = coroutineScope {
            groupIds.map { groupId ->
                async { analyzeGroup(groupId, runToken) }
            }.awaitAll()
        }

        val reports = outcomes.mapNotNull(DailyGroupOutcome::report)
        JChatGPT.logger.info(
            "每日群画像推进完成: selected=${groupIds.size} " +
                    "success=${outcomes.count { it.status == DailyGroupStatus.SUCCESS }} " +
                    "alreadyRunning=${outcomes.count { it.status == DailyGroupStatus.ALREADY_RUNNING }} " +
                    "missing=${outcomes.count { it.status == DailyGroupStatus.MISSING_HISTORY }} " +
                    "stopped=${outcomes.count { it.status == DailyGroupStatus.STOPPED }} " +
                    "failed=${outcomes.count { it.status == DailyGroupStatus.FAILED }} " +
                    "batches=${reports.sumOf(GroupProfileAnalysisReport::processedBatches)} " +
                    "messages=${reports.sumOf(GroupProfileAnalysisReport::processedMessages)} " +
                    "operations=${reports.sumOf(GroupProfileAnalysisReport::appliedOperations)} " +
                    "tokens=${reports.sumOf { it.usage.promptTokens }}/${reports.sumOf { it.usage.completionTokens }} " +
                    "cached=${reports.sumOf { it.usage.cachedTokens }}"
        )
    }

    private suspend fun analyzeGroup(
        groupId: Long,
        runToken: ProfileAnalysisRunToken,
    ): DailyGroupOutcome {
        return try {
            val report = UserProfileAnalysisService.analyzeGroup(
                groupId = groupId,
                maxBatches = Int.MAX_VALUE,
                runToken = runToken,
            ) { progress ->
                JChatGPT.logger.info(
                    "PROFILE_DAILY_BATCH group=$groupId batch=${progress.batchIndex} " +
                            "range=${progress.startTime}-${progress.endTime} " +
                            "messages=${progress.messageCount} users=${progress.analyzedUsers} " +
                            "operations=${progress.appliedOperations} skipped=${progress.skippedOperations} " +
                            "tokens=${progress.usage.promptTokens}/${progress.usage.completionTokens} " +
                            "cached=${progress.usage.cachedTokens}"
                )
            }
            val status = when {
                report.alreadyRunning -> DailyGroupStatus.ALREADY_RUNNING
                report.botId == null -> DailyGroupStatus.MISSING_HISTORY
                report.stopped -> DailyGroupStatus.STOPPED
                else -> DailyGroupStatus.SUCCESS
            }
            DailyGroupOutcome(status, report.takeUnless { report.alreadyRunning || report.botId == null })
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            JChatGPT.logger.error("群 $groupId 每日画像推进失败", cause)
            DailyGroupOutcome(DailyGroupStatus.FAILED)
        }
    }

    private data class DailyGroupOutcome(
        val status: DailyGroupStatus,
        val report: GroupProfileAnalysisReport? = null,
    )

    private enum class DailyGroupStatus {
        SUCCESS,
        ALREADY_RUNNING,
        MISSING_HISTORY,
        STOPPED,
        FAILED,
    }
}

private val PROFILE_DAILY_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder()
    .parseStrict()
    .appendValue(ChronoField.HOUR_OF_DAY, 2)
    .appendLiteral(':')
    .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
    .toFormatter(Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)

internal fun parseProfileDailyUpdateTime(value: String): LocalTime? = runCatching {
    LocalTime.parse(value.trim(), PROFILE_DAILY_TIME_FORMATTER)
}.getOrNull()

internal fun nextProfileDailyUpdateAt(now: ZonedDateTime, scheduledTime: LocalTime): ZonedDateTime {
    val today = now.toLocalDate().atTime(scheduledTime).atZone(now.zone)
    return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(scheduledTime).atZone(now.zone)
}

internal fun oldestAllowedProfilePendingTime(nowEpochSecond: Long, maxPendingAgeDays: Int): Int {
    require(maxPendingAgeDays > 0) { "maxPendingAgeDays must be positive" }
    return (nowEpochSecond - maxPendingAgeDays.toLong() * 24L * 60L * 60L)
        .coerceIn(0L, Int.MAX_VALUE.toLong())
        .toInt()
}
