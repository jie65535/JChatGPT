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
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object PluginCommands : CompositeCommand(
    JChatGPT, "jgpt", description = "J OpenAI ChatGPT"
) {

    @SubCommand
    suspend fun CommandSender.reload() {
        PluginConfig.reload()
        PluginData.reload()
        LargeLanguageModels.reload()
        sendMessage("OK")
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

        if (PluginData.tokenUsageRecords.isEmpty()) {
            sendMessage("暂无 Token 使用记录")
            return
        }

        val cutoff = calculateCutoffTimestamp(days)
        val todayStart = calculateTodayStartTimestamp()

        // 一次遍历计算所有统计数据
        data class Statistics(
            var totalTokens: Int = 0,
            var todayTokens: Int = 0,
            val userTotals: MutableMap<Long, Pair<String, Int>> = mutableMapOf(),
            val groupTotals: MutableMap<Long, Int> = mutableMapOf(),
            val users: MutableSet<Long> = mutableSetOf()
        )

        val stats = PluginData.tokenUsageRecords.fold(Statistics()) { acc, record ->
            if (record.timestamp >= cutoff) {
                acc.totalTokens += record.totalTokens
                acc.users.add(record.userId)

                // 累计用户Token
                val existing = acc.userTotals[record.userId]
                if (existing == null) {
                    acc.userTotals[record.userId] = record.userNickname to record.totalTokens
                } else {
                    acc.userTotals[record.userId] = existing.first to (existing.second + record.totalTokens)
                }

                // 累计群组Token
                record.groupId?.let { groupId ->
                    acc.groupTotals[groupId] = acc.groupTotals.getOrDefault(groupId, 0) + record.totalTokens
                }
            }

            if (record.timestamp >= todayStart) {
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
            appendLine("  /jgpt tokensQuery [userId] [days] - 详细记录")
            appendLine("  /jgpt tokensUserDaily <userId> [days] - 用户日统计")
        }

        sendMessage(response)
    }

    @SubCommand
    suspend fun CommandSender.tokensDaily(days: Int = 7) {
        validateDays(days)

        val cutoff = calculateCutoffTimestamp(days)

        val dailyStats = PluginData.tokenUsageRecords
            .filter { it.timestamp >= cutoff }
            .groupBy {
                LocalDate.ofInstant(
                    Instant.ofEpochSecond(it.timestamp),
                    ZoneId.systemDefault()
                )
            }
            .mapValues { (_, records) ->
                records.sumOf { it.totalTokens }
            }
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

        val userStats = PluginData.tokenUsageRecords
            .groupBy { it.userId }
            .mapValues { (_, records) ->
                Pair(
                    records.first().userNickname,
                    records.sumOf { it.totalTokens }
                )
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

        val groupStats = PluginData.tokenUsageRecords
            .filter { it.groupId != null }
            .groupBy { it.groupId!! }
            .mapValues { (_, records) ->
                records.sumOf { it.totalTokens }
            }
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

        val cutoff = calculateCutoffTimestamp(days)

        val filtered = PluginData.tokenUsageRecords
            .filter { it.timestamp >= cutoff }
            .filter { userId == null || it.userId == userId }
            .sortedByDescending { it.timestamp }
            .take(DEFAULT_QUERY_LIMIT)

        if (filtered.isEmpty()) {
            sendMessage("指定时间范围内无使用记录")
            return
        }

        val response = buildString {
            appendLine("最近 $days 天使用记录（最多显示${DEFAULT_QUERY_LIMIT}条）：")
            appendLine()
            filtered.forEach { record ->
                val time = Instant.ofEpochSecond(record.timestamp)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                val location = if (record.groupId != null) "群${record.groupId}" else "私聊"
                appendLine("[$time] $location - ${record.userNickname}")
                appendLine("  模型: ${record.model}, Tokens: ${formatNumber(record.totalTokens)} " +
                          "(输入: ${formatNumber(record.promptTokens)}, 输出: ${formatNumber(record.completionTokens)})")
                appendLine()
            }
        }
        sendMessage(response)
    }

    @SubCommand
    suspend fun CommandSender.tokensUserDaily(userId: Long, days: Int = 7) {
        validateDays(days)

        val cutoff = calculateCutoffTimestamp(days)

        // 先过滤用户记录，同时获取昵称
        val userRecords = PluginData.tokenUsageRecords
            .filter { it.timestamp >= cutoff && it.userId == userId }

        if (userRecords.isEmpty()) {
            sendMessage("用户 $userId 在指定时间范围内无使用记录")
            return
        }

        val userNickname = userRecords.first().userNickname

        val userDailyStats = userRecords
            .groupBy {
                LocalDate.ofInstant(
                    Instant.ofEpochSecond(it.timestamp),
                    ZoneId.systemDefault()
                )
            }
            .mapValues { (_, records) ->
                records.sumOf { it.totalTokens }
            }
            .toSortedMap()

        val response = buildString {
            appendLine("用户 $userNickname 最近 $days 天 Token 使用统计：")
            appendLine()
            userDailyStats.forEach { (date, total) ->
                appendLine("$date: $total tokens")
            }
            appendLine()
            appendLine("总计: ${formatNumber(userDailyStats.values.sum())} tokens")
        }
        sendMessage(response)
    }

    // ==================== 辅助函数 ====================

    /**
     * 计算截止时间戳（指定天数前的起始时间 00:00:00）
     * 最近N天包含今天，所以要从 (N-1) 天前开始算
     */
    private fun calculateCutoffTimestamp(days: Int): Long {
        return LocalDate.now()
            .minusDays((days - 1).toLong())
            .atStartOfDay(ZoneId.systemDefault())
            .toEpochSecond()
    }

    /**
     * 计算今天的起始时间戳（00:00:00）
     */
    private fun calculateTodayStartTimestamp(): Long {
        return LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toEpochSecond()
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