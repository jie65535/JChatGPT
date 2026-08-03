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
import top.jie65535.mirai.data.PluginData
import top.jie65535.mirai.data.SkillStore
import top.jie65535.mirai.data.TokenUsageStore
import top.jie65535.mirai.llm.LargeLanguageModels
import top.jie65535.mirai.profile.GroupProfileAnalysisReport
import top.jie65535.mirai.profile.ProfileAnalysisReport
import top.jie65535.mirai.profile.ProfileAutoMaintenance
import top.jie65535.mirai.profile.ProfileCategory
import top.jie65535.mirai.profile.ProfileCompactionReport
import top.jie65535.mirai.profile.ProfilePersistentText
import top.jie65535.mirai.profile.UserProfileAnalysisService
import top.jie65535.mirai.profile.UserProfileSnapshot
import top.jie65535.mirai.profile.UserProfileStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object PluginCommands : CompositeCommand(
    JChatGPT, "jgpt", description = "J OpenAI ChatGPT"
) {

    @SubCommand
    suspend fun CommandSender.reload() {
        PluginConfig.reload()
        PluginData.reload()
        LargeLanguageModels.reload()
        if (!PluginConfig.profileEnabled || !PluginConfig.profileAutoUpdateEnabled) {
            ProfileAutoMaintenance.clear()
        }
        SkillStore.reload()
        sendMessage("OK")
    }

    @SubCommand
    suspend fun CommandSender.profileAnalyze(userIds: String, batches: Int = 1) {
        require(batches > 0) { "batches 必须是正数" }
        val parsedUserIds = parseProfileUserIds(userIds)
        val runToken = UserProfileAnalysisService.newRunToken()
        sendMessage("已启动 ${parsedUserIds.size} 个用户的画像分析，每人最多推进 $batches 个批次。")
        parsedUserIds.forEach { userId ->
            JChatGPT.launch {
                try {
                    val report = UserProfileAnalysisService.analyze(userId, batches, runToken) { progress ->
                        JChatGPT.logger.info(
                            "PROFILE_BATCH user=$userId batch=${progress.batchIndex}/$batches " +
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
    suspend fun CommandSender.profileAnalyzeGroup(groupIds: String, batches: Int = 1) {
        require(batches > 0) { "batches 必须是正数" }
        val parsedGroupIds = parseProfileGroupIds(groupIds)
        val runToken = UserProfileAnalysisService.newRunToken()
        sendMessage("已启动 ${parsedGroupIds.size} 个群的画像分析，每群最多推进 $batches 个批次。")
        parsedGroupIds.forEach { groupId ->
            JChatGPT.launch {
                try {
                    val report = UserProfileAnalysisService.analyzeGroup(groupId, batches, runToken) { progress ->
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
                        report.alreadyRunning -> sendMessage("群 $groupId 已有画像分析任务在运行。")
                        report.botId == null -> sendMessage("聊天记录中没有找到群 $groupId 的消息。")
                        else -> sendMessage(formatGroupProfileReport(report))
                    }
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Exception) {
                    JChatGPT.logger.error("群 $groupId 批量画像分析失败", cause)
                    sendMessage("群 $groupId 批量画像分析失败：${cause.message ?: cause::class.simpleName}")
                }
            }
        }
    }

    @SubCommand
    suspend fun CommandSender.profileShow(userId: Long) {
        if (!UserProfileStore.isAvailable) {
            sendMessage("用户画像数据库不可用。")
            return
        }
        val profile = UserProfileStore.load(userId)
        sendMessage(profile?.let(::formatProfile) ?: "用户 $userId 尚无画像。")
    }

    @SubCommand
    suspend fun CommandSender.profileCompact(userIds: String = "") {
        check(PluginConfig.profileEnabled) { "历史用户画像分析未启用" }
        check(UserProfileStore.isAvailable) { "用户画像数据库不可用" }
        val parsedUserIds = if (userIds.isBlank()) {
            withContext(Dispatchers.IO) { UserProfileStore.listUserIds() }
        } else {
            parseProfileUserIds(userIds)
        }
        if (parsedUserIds.isEmpty()) {
            sendMessage("当前没有可压缩的用户画像。")
            return
        }
        val runToken = UserProfileAnalysisService.newRunToken()
        sendMessage("已启动 ${parsedUserIds.size} 个用户的画像压缩反思。")
        parsedUserIds.forEach { userId ->
            JChatGPT.launch {
                try {
                    val report = UserProfileAnalysisService.compact(userId, runToken)
                    when {
                        report.alreadyRunning -> sendMessage("用户 $userId 已有画像压缩任务在运行。")
                        report.stopped -> sendMessage("用户 $userId 的画像压缩已按请求停止，未开始新的压缩轮次。")
                        else -> sendMessage(formatProfileCompactionReport(report))
                    }
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Exception) {
                    JChatGPT.logger.error("用户 $userId 画像压缩失败", cause)
                    sendMessage("用户 $userId 画像压缩失败：${cause.message ?: cause::class.simpleName}")
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

        if (TokenUsageStore.all.isEmpty()) {
            sendMessage("暂无 Token 使用记录")
            return
        }

        val cutoff = calculateCutoffDate(days)
        val today = LocalDate.now().toString()

        val windowed = TokenUsageStore.all.filter { it.date >= cutoff }
        if (windowed.isEmpty()) {
            sendMessage("最近 $days 天无 Token 使用记录")
            return
        }

        // 窗口汇总
        var prompt = 0L; var completion = 0L; var total = 0L; var cached = 0L
        var calls = 0; var todayTotal = 0L
        val users = HashSet<Long>()
        for (r in windowed) {
            prompt += r.promptTokens
            completion += r.completionTokens
            total += r.totalTokens
            cached += r.cachedTokens
            calls += r.callCount
            users.add(r.userId)
            if (r.date == today) todayTotal += r.totalTokens
        }
        val hitRate = if (prompt > 0) cached * 100.0 / prompt else 0.0

        // 每日趋势
        val daily = windowed.groupBy { it.date }
            .mapValues { (_, rs) -> rs.sumOf { it.totalTokens } }
            .toSortedMap()

        // Top 用户
        val topUsers = windowed.groupBy { it.userId }
            .map { (_, rs) ->
                val name = rs.maxByOrNull { it.date }!!.userNickname
                name to rs.sumOf { it.totalTokens }
            }
            .sortedByDescending { it.second }
            .take(TOP_LIMIT)

        // Top 群组：只显示群名，绝不暴露群号（避免被误判宣群）
        val topGroups = windowed.filter { it.groupId != null }
            .groupBy { it.groupId!! }
            .map { (gid, rs) ->
                val name = rs.firstNotNullOfOrNull { r -> r.groupName?.takeIf { it.isNotBlank() } }
                    ?: resolveGroupName(gid)
                name to rs.sumOf { it.totalTokens }
            }
            .sortedByDescending { it.second }
            .take(TOP_LIMIT)

        val response = buildString {
            appendLine("📊 Token 简报 · 最近 $days 天")
            appendLine()
            appendLine("输入 ${formatCompact(prompt)}（缓存命中 ${"%.1f".format(hitRate)}%，省 ${formatCompact(cached)}）")
            appendLine("输出 ${formatCompact(completion)}")
            appendLine("总计 ${formatCompact(total)} ｜ 调用 ${formatNumber(calls)} 次 ｜ 活跃 ${users.size} 人")
            appendLine("今日 ${formatCompact(todayTotal)}")

            if (daily.size > 1) {
                appendLine()
                appendLine("📈 每日趋势")
                daily.forEach { (date, t) ->
                    appendLine("  ${date.substring(5)}  ${formatCompact(t)}")
                }
            }

            if (topUsers.isNotEmpty()) {
                appendLine()
                appendLine("👤 Top 用户")
                topUsers.forEachIndexed { i, (name, t) ->
                    appendLine("  ${i + 1}. $name  ${formatCompact(t)}")
                }
            }

            if (topGroups.isNotEmpty()) {
                appendLine()
                appendLine("👥 Top 群组")
                topGroups.forEachIndexed { i, (name, t) ->
                    appendLine("  ${i + 1}. $name  ${formatCompact(t)}")
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

    private fun formatProfile(profile: UserProfileSnapshot): String = buildString {
        appendLine("用户 ${profile.userId} · 画像 v${profile.version}")
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
private val PROFILE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())
private val PROFILE_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd")
    .withZone(ZoneId.systemDefault())
