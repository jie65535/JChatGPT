package top.jie65535.mirai

import net.mamoe.mirai.console.command.CommandSender
import net.mamoe.mirai.console.command.CompositeCommand
import net.mamoe.mirai.console.permission.PermissionService.Companion.cancel
import net.mamoe.mirai.console.permission.PermissionService.Companion.permit
import net.mamoe.mirai.console.permission.PermitteeId.Companion.permitteeId
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.Member
import net.mamoe.mirai.contact.User
import top.jie65535.mirai.JChatGPT.reload
import java.time.LocalDate

object PluginCommands : CompositeCommand(
    JChatGPT, "jgpt", description = "J OpenAI ChatGPT"
) {

    @SubCommand
    suspend fun CommandSender.reload() {
        PluginConfig.reload()
        PluginData.reload()
        LargeLanguageModels.reload()
        SkillStore.reload()
        sendMessage("OK")
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
    suspend fun CommandSender.setFavor(user: User, value: Int) {
        // 限制好感度值在-100到100之间
        val clampedValue = value.coerceIn(-100, 100)
        // 获取当前的好感度信息
        val currentInfo = PluginData.userFavorability[user.id] ?: FavorabilityInfo(user.id)
        // 创建新的好感度信息，保持原因和印象不变
        val newInfo = currentInfo.copy(value = clampedValue)
        PluginData.userFavorability[user.id] = newInfo
        sendMessage("OK")
    }

    @SubCommand
    suspend fun CommandSender.clearFavor() {
        PluginData.userFavorability.clear()
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

        data class Statistics(
            var totalTokens: Long = 0,
            var todayTokens: Long = 0,
            val userTotals: MutableMap<Long, Pair<String, Long>> = mutableMapOf(),
            val groupTotals: MutableMap<Long, Long> = mutableMapOf(),
            val users: MutableSet<Long> = mutableSetOf()
        )

        val stats = TokenUsageStore.all.fold(Statistics()) { acc, record ->
            if (record.date >= cutoff) {
                acc.totalTokens += record.totalTokens
                acc.users.add(record.userId)

                val existing = acc.userTotals[record.userId]
                if (existing == null) {
                    acc.userTotals[record.userId] = record.userNickname to record.totalTokens
                } else {
                    acc.userTotals[record.userId] = existing.first to (existing.second + record.totalTokens)
                }

                record.groupId?.let { groupId ->
                    acc.groupTotals[groupId] = acc.groupTotals.getOrDefault(groupId, 0L) + record.totalTokens
                }
            }

            if (record.date == today) {
                acc.todayTokens += record.totalTokens
            }

            acc
        }

        val topUser = stats.userTotals.entries.maxByOrNull { it.value.second }
        val topGroup = stats.groupTotals.entries.maxByOrNull { it.value }

        val response = buildString {
            appendLine("📊 Token 使用简报（最近 $days 天）")
            appendLine()
            appendLine("总计: ${formatNumber(stats.totalTokens)} tokens")
            appendLine("今日: ${formatNumber(stats.todayTokens)} tokens")
            appendLine("活跃用户: ${stats.users.size} 人")

            topUser?.let {
                appendLine()
                appendLine("👤 最活跃用户:")
                appendLine("  ${it.value.first} - ${formatNumber(it.value.second)} tokens")
            }

            topGroup?.let {
                appendLine()
                appendLine("👥 最活跃群组:")
                appendLine("  ${it.key} - ${formatNumber(it.value)} tokens")
            }

            appendLine()
            appendLine("📋 详细查询:")
            appendLine("  /jgpt tokensDaily [days]  - 每日统计")
            appendLine("  /jgpt tokensUsers [limit] - 用户排名")
            appendLine("  /jgpt tokensGroups [limit] - 群组排名")
            appendLine("  /jgpt tokensQuery [userId] [days] - 每日逐人记录")
            appendLine("  /jgpt tokensUserDaily <userId> [days] - 用户日统计")
        }

        sendMessage(response)
    }

    @SubCommand
    suspend fun CommandSender.tokensDaily(days: Int = 7) {
        validateDays(days)

        val cutoff = calculateCutoffDate(days)

        val dailyStats = TokenUsageStore.all
            .filter { it.date >= cutoff }
            .groupBy { it.date }
            .mapValues { (_, records) -> records.sumOf { it.totalTokens } }
            .toSortedMap()

        if (dailyStats.isEmpty()) {
            sendMessage("指定时间范围内无使用记录")
            return
        }

        val response = buildString {
            appendLine("最近 $days 天 Token 使用统计：")
            appendLine()
            dailyStats.forEach { (date, total) ->
                appendLine("$date: ${formatNumber(total)} tokens")
            }
        }
        sendMessage(response)
    }

    @SubCommand
    suspend fun CommandSender.tokensUsers(limit: Int = 10) {
        require(limit > 0) { "limit must be positive: $limit" }

        val userStats = TokenUsageStore.all
            .groupBy { it.userId }
            .mapValues { (_, records) ->
                val latest = records.maxByOrNull { it.date }!!
                Pair(latest.userNickname, records.sumOf { it.totalTokens })
            }
            .toList()
            .sortedByDescending { it.second.second }
            .take(limit)

        if (userStats.isEmpty()) {
            sendMessage("暂无使用记录")
            return
        }

        val response = buildString {
            appendLine("Token 使用排名 Top $limit：")
            appendLine()
            userStats.forEach {
                appendLine("- ${it.second.first}(${it.first}): ${formatNumber(it.second.second)} tokens")
            }
        }
        sendMessage(response)
    }

    @SubCommand
    suspend fun CommandSender.tokensGroups(limit: Int = 10) {
        require(limit > 0) { "limit must be positive: $limit" }

        val groupStats = TokenUsageStore.all
            .filter { it.groupId != null }
            .groupBy { it.groupId!! }
            .mapValues { (_, records) -> records.sumOf { it.totalTokens } }
            .toList()
            .sortedByDescending { it.second }
            .take(limit)

        if (groupStats.isEmpty()) {
            sendMessage("暂无群组使用记录")
            return
        }

        val response = buildString {
            appendLine("群组 Token 使用排名 Top $limit：")
            appendLine()
            groupStats.forEach { (groupId, total) ->
                appendLine("- $groupId: ${formatNumber(total)} tokens")
            }
        }
        sendMessage(response)
    }

    @SubCommand
    suspend fun CommandSender.tokensQuery(userId: Long?, days: Int = 7) {
        validateDays(days)

        val cutoff = calculateCutoffDate(days)

        val filtered = TokenUsageStore.all
            .filter { it.date >= cutoff }
            .filter { userId == null || it.userId == userId }
            .sortedWith(compareByDescending<TokenUsageDailyRecord> { it.date }.thenByDescending { it.totalTokens })
            .take(DEFAULT_QUERY_LIMIT)

        if (filtered.isEmpty()) {
            sendMessage("指定时间范围内无使用记录")
            return
        }

        val response = buildString {
            appendLine("最近 $days 天使用记录（最多显示${DEFAULT_QUERY_LIMIT}条，按日聚合）：")
            appendLine()
            filtered.forEach { record ->
                val location = if (record.groupId != null) "群${record.groupId}" else "私聊"
                appendLine("[${record.date}] $location - ${record.userNickname}")
                appendLine("  调用 ${record.callCount} 次, Tokens: ${formatNumber(record.totalTokens)} " +
                          "(输入: ${formatNumber(record.promptTokens)}, 输出: ${formatNumber(record.completionTokens)})")
                appendLine()
            }
        }
        sendMessage(response)
    }

    @SubCommand
    suspend fun CommandSender.tokensUserDaily(userId: Long, days: Int = 7) {
        validateDays(days)

        val cutoff = calculateCutoffDate(days)

        val userRecords = TokenUsageStore.all
            .filter { it.date >= cutoff && it.userId == userId }

        if (userRecords.isEmpty()) {
            sendMessage("用户 $userId 在指定时间范围内无使用记录")
            return
        }

        val userNickname = userRecords.maxByOrNull { it.date }!!.userNickname

        val userDailyStats = userRecords
            .groupBy { it.date }
            .mapValues { (_, records) -> records.sumOf { it.totalTokens } }
            .toSortedMap()

        val response = buildString {
            appendLine("用户 $userNickname 最近 $days 天 Token 使用统计：")
            appendLine()
            userDailyStats.forEach { (date, total) ->
                appendLine("$date: ${formatNumber(total)} tokens")
            }
            appendLine()
            appendLine("总计: ${formatNumber(userDailyStats.values.sum())} tokens")
        }
        sendMessage(response)
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
     * 验证天数参数
     */
    private fun validateDays(days: Int) {
        require(days > 0) { "days must be positive: $days" }
    }
}

// 常量定义
private const val DEFAULT_QUERY_LIMIT = 20