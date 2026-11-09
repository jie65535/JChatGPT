package top.jie65535.mirai.command

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mamoe.mirai.console.command.CommandSender
import net.mamoe.mirai.console.command.CompositeCommand
import net.mamoe.mirai.console.permission.PermissionService.Companion.cancel
import net.mamoe.mirai.console.permission.PermissionService.Companion.permit
import net.mamoe.mirai.console.permission.PermitteeId.Companion.permitteeId
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.Member
import net.mamoe.mirai.contact.User
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.JChatGPT.reload
import top.jie65535.mirai.config.PluginConfig
import top.jie65535.mirai.config.ModelConfig
import top.jie65535.mirai.config.ModelConfigMigration
import top.jie65535.mirai.conversation.ConversationContext
import top.jie65535.mirai.data.PluginData
import top.jie65535.mirai.data.SkillStore
import top.jie65535.mirai.media.ImageAssets
import top.jie65535.mirai.data.TokenUsageStore
import top.jie65535.mirai.llm.LargeLanguageModels
import top.jie65535.mirai.llm.normalizeMaxConcurrentRequests
import top.jie65535.mirai.profile.GroupProfileAnalysisReport
import top.jie65535.mirai.profile.ProfileAnalysisReport
import top.jie65535.mirai.profile.ProfileAutoMaintenance
import top.jie65535.mirai.profile.ProfileCategory
import top.jie65535.mirai.profile.ProfileCompactionReport
import top.jie65535.mirai.profile.ProfileDailyMaintenance
import top.jie65535.mirai.profile.ProfileDailyRequestController
import top.jie65535.mirai.profile.ProfileDailyRunStoppedException
import top.jie65535.mirai.profile.ProfilePersistentText
import top.jie65535.mirai.profile.UserProfileAnalysisService
import top.jie65535.mirai.profile.UserProfileSnapshot
import top.jie65535.mirai.profile.UserProfileStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object PluginCommands : CompositeCommand(
    JChatGPT, "jgpt", description = "J OpenAI ChatGPT"
) {

    @SubCommand
    suspend fun CommandSender.reload() {
        PluginConfig.reload()
        ModelConfig.reload()
        ModelConfigMigration.migrateLoadedConfig()
        PluginData.reload()
        LargeLanguageModels.reload()
        ProfileDailyMaintenance.reload()
        if (!PluginConfig.profileEnabled || !PluginConfig.profileAutoUpdateEnabled) {
            ProfileAutoMaintenance.clear()
        }
        SkillStore.reload()
        if (PluginConfig.memeDir.isNotBlank() && ImageAssets.store != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ImageAssets.requireStore().importDirectory(java.io.File(PluginConfig.memeDir)) }
                    .onFailure { JChatGPT.logger.warning("重新导入本地图片素材失败", it) }
            }
        }
        sendMessage("OK")
    }

    @SubCommand
    suspend fun CommandSender.profileAnalyze(groupId: Long, userIds: String, batches: Int = 1) {
        requireProfileGroupScope(groupId)
        require(batches > 0) { "batches 必须是正数" }
        val parsedUserIds = parseProfileUserIds(userIds)
        val runToken = UserProfileAnalysisService.newRunToken()
        sendMessage("已启动 ${parsedUserIds.size} 个用户的画像分析，每人最多推进 $batches 个批次。")
        parsedUserIds.forEach { userId ->
            JChatGPT.launch {
                try {
                    val report = UserProfileAnalysisService.analyze(groupId, userId, batches, runToken) { progress ->
                        JChatGPT.logger.info(
                            "PROFILE_BATCH group=$groupId user=$userId batch=${progress.batchIndex}/$batches " +
                                    "range=${progress.startTime}-${progress.endTime} " +
                                    "messages=${progress.messageCount} operations=${progress.operationCount} " +
                                    "skipped=${progress.skippedOperationCount} " +
                                    "tokens=${progress.usage.promptTokens}/${progress.usage.completionTokens} " +
                                    "cached=${progress.usage.cachedTokens}"
                        )
                    }
                    when {
                        report.alreadyRunning -> sendMessage("用户 $userId 已有画像分析任务在运行。")
                        report.profile == null -> sendMessage("聊天记录中没有找到用户 $userId 的群聊发言。")
                        else -> sendMessage(formatProfileReport(report))
                    }
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Exception) {
                    JChatGPT.logger.error("用户 $userId 画像分析失败", cause)
                    sendMessage("用户 $userId 画像分析失败：${cause.message ?: cause::class.simpleName}")
                }
            }
        }
    }

    @SubCommand
    suspend fun CommandSender.profileAnalyzeGroup(groupIds: String = "", batches: Int = 1) {
        require(batches > 0) { "batches 必须是正数" }
        val analyzeAllGroups = groupIds.isBlank()
        val minimumMessages = PluginConfig.profileBulkGroupMinPendingMessages.coerceAtLeast(1)
        val parsedGroupIds = if (analyzeAllGroups) {
            UserProfileAnalysisService.listPendingHistoryGroupIds(minimumMessages)
        } else {
            parseProfileGroupIds(groupIds)
        }
        if (parsedGroupIds.isEmpty()) {
            sendMessage(
                if (analyzeAllGroups) {
                    "没有达到启动条件的群画像：历史库中无有效群消息、群已追平，" +
                            "或尚未处理的消息少于 $minimumMessages 条。"
                } else {
                    "没有可推进的指定群画像。"
                }
            )
            return
        }
        val runToken = UserProfileAnalysisService.newRunToken()
        val requestController = ProfileDailyRequestController(
            maxConcurrentRequests = PluginConfig.profileMaxConcurrentRequests,
            maxAttempts = PluginConfig.profileRetryMax.coerceIn(0, 3) + 1,
            // A manual full run uses one shared endpoint. If its first task
            // exhausts retries, probing more groups only repeats the same
            // configuration failure and floods the log.
            maxConsecutiveExhaustedTasks = 1,
        )
        sendMessage(
            "已启动 ${parsedGroupIds.size} 个群的画像分析，每群最多推进 $batches 个批次。" +
                    " 请求并发上限 ${normalizeMaxConcurrentRequests(PluginConfig.profileMaxConcurrentRequests)}。" +
                    if (analyzeAllGroups) " 全量启动门槛 $minimumMessages 条待处理消息。" else ""
        )
        val completedGroups = AtomicInteger()
        val successfulGroups = AtomicInteger()
        val alreadyRunningGroups = AtomicInteger()
        val missingGroups = AtomicInteger()
        val failedGroups = AtomicInteger()
        val stoppedGroups = AtomicInteger()
        val failureLogged = java.util.concurrent.atomic.AtomicBoolean()
        parsedGroupIds.forEach { groupId ->
            JChatGPT.launch {
                try {
                    val report = UserProfileAnalysisService.analyzeGroupControlled(
                        groupId = groupId,
                        maxBatches = batches,
                        runToken = runToken,
                        requestController = requestController,
                    ) { progress ->
                        JChatGPT.logger.info(
                            "PROFILE_GROUP_BATCH group=$groupId batch=${progress.batchIndex}/$batches " +
                                    "range=${progress.startTime}-${progress.endTime} " +
                                    "messages=${progress.messageCount} users=${progress.analyzedUsers} " +
                                    "operations=${progress.appliedOperations} skipped=${progress.skippedOperations} " +
                                    "tokens=${progress.usage.promptTokens}/${progress.usage.completionTokens} " +
                                    "cached=${progress.usage.cachedTokens}"
                        )
                    }
                    when {
                        report.alreadyRunning -> {
                            alreadyRunningGroups.incrementAndGet()
                            if (analyzeAllGroups) {
                                JChatGPT.logger.info("群 $groupId 已有画像分析任务在运行")
                            } else {
                                sendMessage("群 $groupId 已有画像分析任务在运行。")
                            }
                        }

                        report.botId == null -> {
                            missingGroups.incrementAndGet()
                            if (analyzeAllGroups) {
                                JChatGPT.logger.warning("聊天记录中没有找到群 $groupId 的消息")
                            } else {
                                sendMessage("聊天记录中没有找到群 $groupId 的消息。")
                            }
                        }

                        else -> {
                            successfulGroups.incrementAndGet()
                            val resultMessage = formatGroupProfileReport(report)
                            if (analyzeAllGroups) {
                                JChatGPT.logger.info(resultMessage)
                            } else {
                                sendMessage(resultMessage)
                            }
                        }
                    }
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: ProfileDailyRunStoppedException) {
                    stoppedGroups.incrementAndGet()
                } catch (cause: Exception) {
                    failedGroups.incrementAndGet()
                    if (failureLogged.compareAndSet(false, true)) {
                        JChatGPT.logger.error("群 $groupId 批量画像分析失败，本轮仅输出一次代表性异常", cause)
                    }
                    if (!analyzeAllGroups) {
                        sendMessage("群 $groupId 批量画像分析失败：${cause.message ?: cause::class.simpleName}")
                    }
                } finally {
                    if (analyzeAllGroups && completedGroups.incrementAndGet() == parsedGroupIds.size) {
                        sendMessage(
                            "全量群画像分析完成：成功 ${successfulGroups.get()}，" +
                                    "已在运行 ${alreadyRunningGroups.get()}，" +
                                    "无历史 ${missingGroups.get()}，失败 ${failedGroups.get()}，" +
                                    "停止 ${stoppedGroups.get()}。"
                        )
                    }
                }
            }
        }
    }

    @SubCommand
    suspend fun CommandSender.profileShow(groupId: Long, userId: Long) {
        requireProfileGroupScope(groupId)
        if (!UserProfileStore.isAvailable) {
            sendMessage("用户画像数据库不可用。")
            return
        }
        val profile = withContext(Dispatchers.IO) { UserProfileStore.load(groupId, userId) }
        sendMessage(profile?.let(::formatProfile) ?: "群 $groupId 的用户 $userId 尚无画像。")
    }

    @SubCommand
    suspend fun CommandSender.profileCompact(groupId: Long, userIds: String = "") {
        requireProfileGroupScope(groupId)
        check(PluginConfig.profileEnabled) { "历史用户画像分析未启用" }
        check(UserProfileStore.isAvailable) { "用户画像数据库不可用" }
        val compactAllUsers = userIds.isBlank()
        val (parsedUserIds, skippedBelowThreshold) = if (compactAllUsers) {
            withContext(Dispatchers.IO) {
                val allUserIds = UserProfileStore.listUserIds(groupId)
                val candidates = UserProfileStore.listUserIdsWithMinimumItems(
                    groupId, BULK_PROFILE_COMPACTION_MIN_ITEMS
                )
                candidates to (allUserIds.size - candidates.size)
            }
        } else {
            parseProfileUserIds(userIds) to 0
        }
        if (parsedUserIds.isEmpty()) {
            val skipped = if (compactAllUsers && skippedBelowThreshold > 0) {
                "，已跳过 $skippedBelowThreshold 个不足 $BULK_PROFILE_COMPACTION_MIN_ITEMS 条的画像"
            } else {
                ""
            }
            sendMessage("当前没有达到压缩门槛的用户画像$skipped。")
            return
        }
        val runToken = UserProfileAnalysisService.newRunToken()
        if (compactAllUsers) {
            sendMessage(
                "已启动 ${parsedUserIds.size} 个用户的画像压缩反思；" +
                        "全量门槛为至少 $BULK_PROFILE_COMPACTION_MIN_ITEMS 条，" +
                        "已跳过 $skippedBelowThreshold 个未达门槛的画像。"
            )
        } else {
            sendMessage("已启动 ${parsedUserIds.size} 个指定用户的画像压缩反思。")
        }

        val completedUsers = AtomicInteger()
        val successfulUsers = AtomicInteger()
        val changedUsers = AtomicInteger()
        val alreadyRunningUsers = AtomicInteger()
        val stoppedUsers = AtomicInteger()
        val failedUsers = AtomicInteger()
        val beforeItems = AtomicLong()
        val afterItems = AtomicLong()
        val mergedGroups = AtomicLong()
        val rewrittenItems = AtomicLong()
        val deletedItems = AtomicLong()
        val repairedRanges = AtomicLong()
        val promptTokens = AtomicLong()
        val completionTokens = AtomicLong()
        val cachedTokens = AtomicLong()
        parsedUserIds.forEach { userId ->
            JChatGPT.launch {
                try {
                    val report = UserProfileAnalysisService.compact(groupId, userId, runToken)
                    when {
                        report.alreadyRunning -> {
                            alreadyRunningUsers.incrementAndGet()
                            if (!compactAllUsers) sendMessage("用户 $userId 已有画像压缩任务在运行。")
                        }
                        report.stopped -> {
                            stoppedUsers.incrementAndGet()
                            if (!compactAllUsers) {
                                sendMessage("用户 $userId 的画像压缩已按请求停止，未开始新的压缩轮次。")
                            }
                        }
                        else -> {
                            successfulUsers.incrementAndGet()
                            if (report.hasCompactionChanges()) changedUsers.incrementAndGet()
                            beforeItems.addAndGet(report.beforeItems.toLong())
                            afterItems.addAndGet(report.afterItems.toLong())
                            mergedGroups.addAndGet(report.mergedGroups.toLong())
                            rewrittenItems.addAndGet(report.rewrittenItems.toLong())
                            deletedItems.addAndGet(report.deletedItems.toLong())
                            repairedRanges.addAndGet(report.repairedItemRanges.toLong())
                            promptTokens.addAndGet(report.usage.promptTokens.toLong())
                            completionTokens.addAndGet(report.usage.completionTokens.toLong())
                            cachedTokens.addAndGet(report.usage.cachedTokens.toLong())
                            if (!compactAllUsers) sendMessage(formatProfileCompactionReport(report))
                        }
                    }
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Exception) {
                    failedUsers.incrementAndGet()
                    JChatGPT.logger.error("用户 $userId 画像压缩失败", cause)
                    if (!compactAllUsers) {
                        sendMessage("用户 $userId 画像压缩失败：${cause.message ?: cause::class.simpleName}")
                    }
                } finally {
                    if (compactAllUsers && completedUsers.incrementAndGet() == parsedUserIds.size) {
                        val successful = successfulUsers.get()
                        sendMessage(
                            "全量画像压缩完成：成功 $successful（有变更 ${changedUsers.get()}，" +
                                    "无变更 ${successful - changedUsers.get()}），" +
                                    "已在运行 ${alreadyRunningUsers.get()}，已停止 ${stoppedUsers.get()}，" +
                                    "失败 ${failedUsers.get()}；跳过未达门槛 $skippedBelowThreshold。\n" +
                                    "条目 ${formatNumber(beforeItems.get())} -> ${formatNumber(afterItems.get())}，" +
                                    "合并 ${formatNumber(mergedGroups.get())} 组，" +
                                    "改写 ${formatNumber(rewrittenItems.get())} 条，" +
                                    "删除 ${formatNumber(deletedItems.get())} 条，" +
                                    "修复 ${formatNumber(repairedRanges.get())} 条时间范围。\n" +
                                    "Token：输入 ${formatNumber(promptTokens.get())}，" +
                                    "输出 ${formatNumber(completionTokens.get())}，" +
                                    "缓存命中 ${formatNumber(cachedTokens.get())}"
                        )
                    }
                }
            }
        }
    }

    @SubCommand
    suspend fun CommandSender.profileStop() {
        val report = UserProfileAnalysisService.stopAll()
        sendMessage(
            "已发出画像任务停止请求；当前检测到 ${report.totalTasks} 个任务" +
                    "（用户分析 ${report.userTasks}，群分析 ${report.groupTasks}，压缩 ${report.compactionTasks}）。" +
                    "当前轮次会正常完成，之后不再开始新一轮。"
        )
    }

    private fun CommandSender.requireProfileGroupScope(groupId: Long) {
        require(groupId > 0) { "groupId 必须是正数" }
        require(subject !is Group || subject?.id == groupId) { "群聊中只能查看或维护当前群的成员画像" }
    }

    @SubCommand
    suspend fun CommandSender.skills() {
        val all = SkillStore.all
        if (all.isEmpty()) {
            sendMessage("暂无技能")
            return
        }
        val response = buildString {
            appendLine("当前技能（共 ${all.size} 个）：")
            all.forEach { appendLine("- ${it.name}: ${it.description}") }
        }
        sendMessage(response.trim())
    }

    @SubCommand
    suspend fun CommandSender.enable(contact: Contact) {
        when (contact) {
            is Member -> contact.permitteeId.permit(JChatGPT.chatPermission)
            is User -> contact.permitteeId.permit(JChatGPT.chatPermission)
            is Group -> contact.permitteeId.permit(JChatGPT.chatPermission)
        }
        sendMessage("OK")
    }

    @SubCommand
    suspend fun CommandSender.disable(contact: Contact) {
        when (contact) {
            is Member -> contact.permitteeId.cancel(JChatGPT.chatPermission, false)
            is User -> contact.permitteeId.cancel(JChatGPT.chatPermission, false)
            is Group -> contact.permitteeId.cancel(JChatGPT.chatPermission, false)
        }
        sendMessage("OK")
    }

    @SubCommand
    suspend fun CommandSender.clearMemory() {
        PluginData.contactMemory.clear()
        sendMessage("OK")
    }

    @SubCommand
    suspend fun CommandSender.clearContextCache() {
        JChatGPT.clearContextCache()
        sendMessage("已清空所有对话上下文缓存")
    }

    @SubCommand
    suspend fun CommandSender.tokens(days: Int = 7) {
        validateDays(days)

        if (!TokenUsageStore.isAvailable) {
            sendMessage("Token SQLite 尚未初始化")
            return
        }

        val cutoff = calculateCutoffDate(days)
        val summary = runCatching { TokenUsageStore.summary(cutoff, rankingLimit = 100) }
            .getOrElse {
                sendMessage("读取 Token 使用记录失败：${it.message ?: it::class.simpleName}")
                return
            }
        if (!TokenUsageStore.hasAny(cutoff)) {
            sendMessage("暂无 Token 使用记录")
            return
        }

        val hitRate = if (summary.promptTokens > 0) {
            summary.cachedTokens * 100.0 / summary.promptTokens
        } else 0.0

        val response = buildString {
            appendLine("📊 Token 简报 · 最近 $days 天")
            appendLine()
            appendLine("输入 ${formatCompact(summary.promptTokens)}（缓存命中 ${"%.1f".format(hitRate)}%，省 ${formatCompact(summary.cachedTokens)}）")
            appendLine("输出 ${formatCompact(summary.completionTokens)}")
            appendLine("总计 ${formatCompact(summary.totalTokens)} ｜ 调用 ${formatNumber(summary.callCount)} 次 ｜ 活跃 ${summary.activeUsers} 人")
            if (summary.allCallCount != summary.callCount) {
                appendLine("全部模型调用 ${formatNumber(summary.allCallCount)} 次")
            }
            appendLine("今日 ${formatCompact(summary.todayTotal)}")

            if (summary.daily.size > 1) {
                appendLine()
                appendLine("📈 每日趋势")
                summary.daily.forEach { daily ->
                    appendLine("  ${daily.date.substring(5)}  ${formatCompact(daily.totalTokens)}")
                }
            }

            if (summary.topUsers.isNotEmpty()) {
                appendLine()
                appendLine("👤 Top 用户")
                summary.topUsers.forEachIndexed { i, ranking ->
                    appendLine("  ${i + 1}. ${ranking.name.ifBlank { ranking.id.toString() }}  ${formatCompact(ranking.totalTokens)}")
                }
            }

            if (summary.topGroups.isNotEmpty()) {
                appendLine()
                appendLine("👥 Top 群组")
                summary.topGroups.forEachIndexed { i, ranking ->
                    val name = ranking.name.ifBlank { resolveGroupName(ranking.id) }
                    appendLine("  ${i + 1}. $name  ${formatCompact(ranking.totalTokens)}")
                }
            }

            if (summary.models.size > 1) {
                appendLine()
                appendLine("🤖 模型")
                summary.models.take(TOP_LIMIT).forEach { model ->
                    appendLine("  ${model.provider}/${model.model}  ${formatCompact(model.totalTokens)}")
                }
            }

            val tokenUsageByKind = summary.breakdown.asSequence()
                .filter { it.unit == "tokens" }
                .groupBy { it.usageKind }
                .mapValues { (_, usage) -> usage.sumOf { it.totalUnits } }
                .entries
                .sortedByDescending { it.value }
            if (tokenUsageByKind.size > 1 || tokenUsageByKind.firstOrNull()?.key != "chat") {
                appendLine()
                appendLine("Token 用途")
                tokenUsageByKind.take(TOP_LIMIT).forEach { (kind, total) ->
                    appendLine("  $kind  ${formatCompact(total)}")
                }
            }

            val otherUsage = summary.breakdown.filter { it.unit != "tokens" }
            if (otherUsage.isNotEmpty()) {
                appendLine()
                appendLine("其他模型用量")
                otherUsage.take(TOP_LIMIT).forEach { usage ->
                    appendLine(
                        "  ${usage.provider}/${usage.model} ${usage.usageKind}  " +
                                "${formatCompact(usage.totalUnits)} ${usage.unit}"
                    )
                }
            }
        }
        sendMessage(response.trim())
    }

    // ==================== 辅助函数 ====================

    /**
     * 计算截止日期字符串（指定天数前的日期，含今天共 days 天）
     */
    private fun calculateCutoffDate(days: Int): String {
        return LocalDate.now().minusDays((days - 1).toLong()).toString()
    }

    /**
     * 格式化数字（添加千位分隔符）
     */
    private fun formatNumber(number: Number): String {
        return String.format("%,d", number.toLong())
    }

    /**
     * 大数压缩为 K/M，简报用，避免一屏全是逗号长串。
     */
    private fun formatCompact(n: Long): String = when {
        n >= 1_000_000 -> "%.2fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fK".format(n / 1_000.0)
        else -> n.toString()
    }

    /**
     * 解析群名：记录里没存到群名时（旧数据）才回退到在线 Bot 查询，
     * 仍查不到则用占位文案，绝不直接展示群号。
     */
    private fun resolveGroupName(groupId: Long): String {
        return net.mamoe.mirai.Bot.instances
            .firstNotNullOfOrNull { it.getGroup(groupId)?.name }
            ?: "未知群聊"
    }

    /**
     * 验证天数参数
     */
    private fun validateDays(days: Int) {
        require(days > 0) { "days must be positive: $days" }
    }

    private fun formatProfileReport(report: ProfileAnalysisReport): String = buildString {
        appendLine(
            "画像分析完成：${report.processedBatches} 批，${report.processedMessages} 条上下文消息，" +
                    "${report.appliedOperations} 项变更，跳过 ${report.skippedOperations} 项无效建议"
        )
        appendLine(
            "Token：输入 ${formatNumber(report.usage.promptTokens)}，输出 " +
                    "${formatNumber(report.usage.completionTokens)}，缓存命中 " +
                    formatNumber(report.usage.cachedTokens)
        )
        appendLine("状态：${profileAnalysisStatus(report.caughtUp, report.stopped)}")
        append(formatProfile(checkNotNull(report.profile)))
    }.trim()

    private fun formatGroupProfileReport(report: GroupProfileAnalysisReport): String = buildString {
        appendLine(
            "群 ${report.groupId} 画像分析完成：${report.processedBatches} 批，" +
                    "${report.processedMessages} 条消息，${report.analyzedUsers} 用户人次，" +
                    "${report.appliedOperations} 项变更，跳过 ${report.skippedOperations} 项无效建议"
        )
        appendLine(
            "Token：输入 ${formatNumber(report.usage.promptTokens)}，输出 " +
                    "${formatNumber(report.usage.completionTokens)}，缓存命中 " +
                    formatNumber(report.usage.cachedTokens)
        )
        appendLine("群历史覆盖至 ${formatProfileTime(report.cursorTime)}")
        append("状态：${profileAnalysisStatus(report.caughtUp, report.stopped)}")
    }.trim()

    private fun profileAnalysisStatus(caughtUp: Boolean, stopped: Boolean): String = when {
        caughtUp -> "已追平当前快照"
        stopped -> "已按请求停止，可继续推进"
        else -> "可继续推进"
    }

    private fun formatProfileCompactionReport(report: ProfileCompactionReport): String = buildString {
        appendLine(
            "画像压缩完成：${report.beforeItems} -> ${report.afterItems} 条，" +
                    "合并 ${report.mergedGroups} 组，改写 ${report.rewrittenItems} 条，删除 ${report.deletedItems} 条，" +
                    "修复 ${report.repairedItemRanges} 条时间范围，" +
                    "摘要${if (report.summaryChanged) "已重写" else "未变"}，" +
                    "跳过 ${report.skippedOperations} 项不安全建议"
        )
        appendLine(
            "Token：输入 ${formatNumber(report.usage.promptTokens)}，输出 " +
                    "${formatNumber(report.usage.completionTokens)}，缓存命中 " +
                    formatNumber(report.usage.cachedTokens)
        )
        append(formatProfile(report.profile))
    }.trim()

    private fun ProfileCompactionReport.hasCompactionChanges(): Boolean =
        beforeItems != afterItems || mergedGroups > 0 || rewrittenItems > 0 || deletedItems > 0 ||
                repairedItemRanges > 0 || summaryChanged

    private fun formatProfile(profile: UserProfileSnapshot): String = buildString {
        appendLine("群 ${profile.groupId} · 用户 ${profile.userId} · 画像 v${profile.version}")
        if (profile.cursorTime <= 0) {
            appendLine("历史回顾：尚未开始（当前画像来自自动会话归纳）")
        } else {
            appendLine("历史回顾覆盖至 ${formatProfileTime(profile.cursorTime)}")
        }
        val summary = ProfilePersistentText.summaryForDisplay(profile.summary)
        appendLine("摘要：${summary.ifBlank { "（暂无）" }}")
        if (profile.items.isEmpty()) {
            append("条目：（暂无）")
        } else {
            appendLine("条目：")
            profile.items.forEach { item ->
                append("- [").append(item.category.name.lowercase()).append('/')
                    .append(item.confidence.name.lowercase()).append(" · 记录于 ")
                    .append(formatProfileDate(item.firstSeenAt))
                if (item.lastConfirmedAt != item.firstSeenAt) {
                    append("，确认至 ").append(formatProfileDate(item.lastConfirmedAt))
                }
                append("] ")
                val relatedUserId = item.relatedUserId
                if (item.category == ProfileCategory.RELATIONSHIP_NOTE && relatedUserId != null) {
                    val relatedName = PluginData.userFavorability[relatedUserId]?.name.orEmpty()
                    if (relatedName.isBlank()) {
                        append("与用户 ").append(relatedUserId).append("：")
                    } else {
                        append('与').append(relatedName).append('（').append(relatedUserId).append("）：")
                    }
                }
                append(ProfilePersistentText.itemForDisplay(
                    item.content,
                    relationship = item.category == ProfileCategory.RELATIONSHIP_NOTE,
                ))
                appendLine()
            }
        }
    }.trim()

    private fun formatProfileTime(epochSecond: Int): String =
        PROFILE_TIME_FORMATTER.format(Instant.ofEpochSecond(epochSecond.toLong()))

    private fun formatProfileDate(epochSecond: Int): String =
        PROFILE_DATE_FORMATTER.format(Instant.ofEpochSecond(epochSecond.toLong()))
}

// 常量定义
private const val TOP_LIMIT = 5
private const val BULK_PROFILE_COMPACTION_MIN_ITEMS = 10
private val PROFILE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())
private val PROFILE_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd")
    .withZone(ZoneId.systemDefault())
