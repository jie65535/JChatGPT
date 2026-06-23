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
}

// 常量定义
private const val TOP_LIMIT = 5