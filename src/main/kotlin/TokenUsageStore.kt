package top.jie65535.mirai

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Token使用日聚合存储。独立于 mamoe 的 plugin data 系统，直接管 JSON 文件，
 * 避免 yamlkt 在大数据量下编/解码不互通的 bug。
 */
object TokenUsageStore {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE
    private val listSerializer = ListSerializer(TokenUsageDailyRecord.serializer())

    private lateinit var file: File
    private val records = mutableListOf<TokenUsageDailyRecord>()

    /**
     * 在 onEnable 中调用一次，传入插件数据目录。
     */
    fun init(dataFolder: File) {
        file = File(dataFolder, "token_usage.json")
        records.clear()
        if (file.exists() && file.length() > 0) {
            try {
                records.addAll(json.decodeFromString(listSerializer, file.readText()))
            } catch (_: Exception) {
                // 加载失败不阻塞插件启动，备份原文件后从空开始
                val backup = File(file.parentFile, "token_usage.json.broken-${System.currentTimeMillis()}")
                file.copyTo(backup, overwrite = true)
            }
        }
    }

    val all: List<TokenUsageDailyRecord> get() = records

    /**
     * 将一次调用的 token 用量累加到当日聚合行；若不存在则创建。写盘失败不抛。
     */
    @Synchronized
    fun record(
        timestamp: Long,
        userId: Long,
        userNickname: String,
        groupId: Long?,
        promptTokens: Int,
        completionTokens: Int,
        totalTokens: Int
    ) {
        val date = LocalDate.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault())
            .format(dateFmt)
        val nickname = sanitizeNickname(userNickname)
        val idx = records.indexOfFirst {
            it.date == date && it.userId == userId && it.groupId == groupId
        }
        if (idx >= 0) {
            val r = records[idx]
            records[idx] = r.copy(
                userNickname = nickname.ifEmpty { r.userNickname },
                promptTokens = r.promptTokens + promptTokens,
                completionTokens = r.completionTokens + completionTokens,
                totalTokens = r.totalTokens + totalTokens,
                callCount = r.callCount + 1
            )
        } else {
            records.add(
                TokenUsageDailyRecord(
                    date = date,
                    userId = userId,
                    userNickname = nickname,
                    groupId = groupId,
                    promptTokens = promptTokens.toLong(),
                    completionTokens = completionTokens.toLong(),
                    totalTokens = totalTokens.toLong(),
                    callCount = 1
                )
            )
        }
        save()
    }

    /** 把控制字符压成空格，避免昵称里的换行/零宽字符把 JSON/展示弄乱。 */
    private fun sanitizeNickname(s: String): String {
        if (s.isEmpty()) return s
        val cleaned = buildString(s.length) {
            for (c in s) {
                if (c == ' ' || (!c.isISOControl() && c.category != CharCategory.FORMAT)) append(c)
                else append(' ')
            }
        }
        return cleaned.trim().replace(Regex(" {2,}"), " ")
    }

    private fun save() {
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(listSerializer, records))
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        } catch (_: Exception) {
            // 写盘失败由日志/上层关心，这里不抛断对话流程
        }
    }
}
