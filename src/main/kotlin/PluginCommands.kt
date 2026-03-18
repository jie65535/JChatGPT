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
    suspend fun CommandSender.tokens() {
        sendMessage("请使用子命令：daily, users, groups, query")
    }

    @SubCommand
    suspend fun CommandSender.tokensDaily(days: Int = 7) {
        val now = Instant.now().epochSecond
        val secondsPerDay = 86400
        val cutoff = now - (days * secondsPerDay)

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
                appendLine("$date: $total tokens")
            }
        }
        sendMessage(response)
    }

    @SubCommand
    suspend fun CommandSender.tokensUsers(limit: Int = 10) {
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
                appendLine("- ${it.second.first}(${it.first}): ${it.second.second} tokens")
            }
        }
        sendMessage(response)
    }

    @SubCommand
    suspend fun CommandSender.tokensGroups(limit: Int = 10) {
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
                appendLine("- $groupId: $total tokens")
            }
        }
        sendMessage(response)
    }

    @SubCommand
    suspend fun CommandSender.tokensQuery(userId: Long?, days: Int = 7) {
        val now = Instant.now().epochSecond
        val cutoff = now - (days * 86400)

        val filtered = PluginData.tokenUsageRecords
            .filter { it.timestamp >= cutoff }
            .filter { userId == null || it.userId == userId }
            .sortedByDescending { it.timestamp }
            .take(20)

        if (filtered.isEmpty()) {
            sendMessage("指定时间范围内无使用记录")
            return
        }

        val response = buildString {
            appendLine("最近 $days 天使用记录（最多显示20条）：")
            appendLine()
            filtered.forEach { record ->
                val time = Instant.ofEpochSecond(record.timestamp)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                val location = if (record.groupId != null) "群${record.groupId}" else "私聊"
                appendLine("[$time] $location - ${record.userNickname}")
                appendLine("  模型: ${record.model}, Tokens: ${record.totalTokens} " +
                          "(输入: ${record.promptTokens}, 输出: ${record.completionTokens})")
                appendLine()
            }
        }
        sendMessage(response)
    }
}