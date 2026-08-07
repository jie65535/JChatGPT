package top.jie65535.mirai.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.sqlite.SQLiteConfig
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * SQLite model-usage ledger. New calls are stored individually; old daily JSON
 * aggregates are imported as rows marked [TokenUsageRecord.detailed] = false.
 */
object TokenUsageStore {
    private const val SCHEMA_VERSION = 2
    private const val BUSY_TIMEOUT_MS = 30_000
    private const val DATABASE_NAME = "chat-history.sqlite"
    private const val LEGACY_FILE_NAME = "token_usage.json"
    private const val LEGACY_FINGERPRINT_KEY = "legacy_json_sha256"
    private const val TOP_LIMIT = 5
    private const val MAX_RANKING_LIMIT = 100

    private val lifecycleLock = Any()
    private val writeLock = Any()
    private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE
    private val zone = ZoneId.systemDefault()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val legacySerializer = ListSerializer(TokenUsageDailyRecord.serializer())

    @Volatile
    private var initialized = false
    private lateinit var databaseFile: File
    private var writeConnection: Connection? = null
    private var warningLogger: ((String, Throwable?) -> Unit)? = null

    val isAvailable: Boolean
        get() = initialized

    fun init(
        dataFolder: File,
        onWarning: (String, Throwable?) -> Unit = { _, _ -> },
    ) {
        synchronized(lifecycleLock) {
            if (initialized) return
            Class.forName("org.sqlite.JDBC")
            dataFolder.mkdirs()
            databaseFile = dataFolder.resolve(DATABASE_NAME)
            warningLogger = onWarning

            val connection = openConnection(databaseFile)
            try {
                configureWriteConnection(connection)
                createSchema(connection)
                writeConnection = connection
                initialized = true
                importLegacyJson(dataFolder.resolve(LEGACY_FILE_NAME))
            } catch (cause: Throwable) {
                connection.close()
                warningLogger = null
                throw cause
            }
        }
    }

    fun close() {
        synchronized(lifecycleLock) {
            if (!initialized) return
            synchronized(writeLock) {
                writeConnection?.let { connection ->
                    runCatching {
                        connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
                    }.onFailure { warn("Token SQLite WAL checkpoint 失败", it) }
                    connection.close()
                }
                writeConnection = null
                initialized = false
                warningLogger = null
            }
        }
    }

    /** Records one successful chat-model response. Storage failures are non-fatal. */
    fun record(
        timestamp: Long,
        botId: Long,
        userId: Long,
        userNickname: String,
        groupId: Long?,
        groupName: String?,
        endpointLabel: String?,
        apiBaseUrl: String?,
        model: String?,
        promptTokens: Int,
        completionTokens: Int,
        totalTokens: Int,
        cachedTokens: Int,
    ) {
        recordUsage(
            ModelUsageEvent(
                timestamp = timestamp,
                botId = botId,
                userId = userId,
                userNickname = userNickname,
                groupId = groupId,
                groupName = groupName,
                endpointLabel = endpointLabel,
                modelAlias = endpointLabel,
                provider = providerFor(apiBaseUrl),
                model = model,
                usageKind = "chat",
                inputUnits = promptTokens.toLong().coerceAtLeast(0),
                outputUnits = completionTokens.toLong().coerceAtLeast(0),
                totalUnits = totalTokens.toLong().coerceAtLeast(0),
                promptTokens = promptTokens.toLong().coerceAtLeast(0),
                completionTokens = completionTokens.toLong().coerceAtLeast(0),
                totalTokens = totalTokens.toLong().coerceAtLeast(0),
                cachedTokens = cachedTokens.toLong().coerceAtLeast(0),
            )
        )
    }

    fun recordUsage(event: ModelUsageEvent) {
        if (!initialized) return
        runCatching {
            withWriteConnection { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO token_usage_record(
                        occurred_at, usage_date, bot_id, user_id, user_nickname,
                        group_id, group_name, endpoint_label, model_alias, provider, model,
                        usage_kind, unit, input_units, output_units, total_units,
                        prompt_tokens, completion_tokens, total_tokens, cached_tokens,
                        call_count, detailed
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1)
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, event.timestamp)
                    statement.setString(2, dateFor(event.timestamp))
                    statement.setLong(3, event.botId)
                    statement.setLong(4, event.userId)
                    statement.setString(5, sanitizeNickname(event.userNickname))
                    if (event.groupId == null) statement.setNull(6, java.sql.Types.INTEGER) else statement.setLong(6, event.groupId)
                    if (event.groupName == null) statement.setNull(7, java.sql.Types.VARCHAR)
                    else statement.setString(7, sanitizeNickname(event.groupName))
                    statement.setString(8, event.endpointLabel?.takeIf(String::isNotBlank))
                    statement.setString(9, event.modelAlias?.takeIf(String::isNotBlank))
                    statement.setString(10, event.provider?.takeIf(String::isNotBlank))
                    statement.setString(11, event.model?.takeIf(String::isNotBlank))
                    statement.setString(12, event.usageKind.ifBlank { "other" })
                    statement.setString(13, event.unit.ifBlank { "units" })
                    statement.setLong(14, event.inputUnits.coerceAtLeast(0))
                    statement.setLong(15, event.outputUnits.coerceAtLeast(0))
                    statement.setLong(16, event.totalUnits.coerceAtLeast(0))
                    statement.setLong(17, event.promptTokens.coerceAtLeast(0))
                    statement.setLong(18, event.completionTokens.coerceAtLeast(0))
                    statement.setLong(19, event.totalTokens.coerceAtLeast(0))
                    statement.setLong(20, event.cachedTokens.coerceAtLeast(0))
                    statement.executeUpdate()
                }
            }
        }.onFailure { warn("保存模型用量记录失败", it) }
    }

    fun summary(
        startDate: String,
        endDate: String = LocalDate.now(zone).format(dateFmt),
        botId: Long? = null,
        userId: Long? = null,
        groupId: Long? = null,
        privateOnly: Boolean = false,
        model: String? = null,
        usageKind: String? = null,
        rankingLimit: Int = TOP_LIMIT,
    ): TokenUsageSummary {
        check(initialized) { "Token SQLite 尚未初始化" }
        require(groupId == null || !privateOnly) { "groupId and privateOnly cannot be used together" }
        require(rankingLimit > 0) { "rankingLimit must be positive" }
        val normalizedRankingLimit = rankingLimit.coerceAtMost(MAX_RANKING_LIMIT)
        val filter = Filter(
            startDate,
            endDate,
            botId,
            userId,
            groupId,
            privateOnly,
            model?.trim()?.takeIf(String::isNotBlank),
            usageKind?.trim()?.takeIf(String::isNotBlank),
        )
        return openReadConnection(databaseFile).use { connection ->
            connection.autoCommit = false
            try {
                val aggregate = queryAggregate(connection, filter, tokenOnly = true)
                val allAggregate = queryAggregate(connection, filter, tokenOnly = false)
                val daily = queryDaily(connection, filter)
                val topUsers = queryTopUsers(connection, filter, normalizedRankingLimit)
                val topGroups = queryTopGroups(connection, filter, normalizedRankingLimit)
                val models = queryModels(connection, filter, normalizedRankingLimit)
                val breakdown = queryBreakdown(connection, filter, normalizedRankingLimit)
                val userUsage = queryUserUsage(connection, filter, normalizedRankingLimit)
                val usageDaily = queryUsageDaily(connection, filter)
                TokenUsageSummary(
                    promptTokens = aggregate.promptTokens,
                    completionTokens = aggregate.completionTokens,
                    totalTokens = aggregate.totalTokens,
                    cachedTokens = aggregate.cachedTokens,
                    callCount = aggregate.callCount,
                    activeUsers = aggregate.activeUsers,
                    todayTotal = queryTodayTotal(connection, filter),
                    daily = daily,
                    topUsers = topUsers,
                    topGroups = topGroups,
                    models = models,
                    allCallCount = allAggregate.callCount,
                    allActiveUsers = allAggregate.activeUsers,
                    breakdown = breakdown,
                    userUsage = userUsage,
                    usageDaily = usageDaily,
                )
            } finally {
                connection.rollback()
            }
        }
    }

    fun recent(
        limit: Int = 20,
        startDate: String? = null,
        endDate: String? = null,
        botId: Long? = null,
        userId: Long? = null,
        groupId: Long? = null,
        privateOnly: Boolean = false,
        model: String? = null,
        usageKind: String? = null,
    ): List<TokenUsageRecord> {
        check(initialized) { "Token SQLite 尚未初始化" }
        require(limit > 0) { "limit must be positive" }
        require(groupId == null || !privateOnly) { "groupId and privateOnly cannot be used together" }
        val filter = Filter(
            startDate,
            endDate,
            botId,
            userId,
            groupId,
            privateOnly,
            model?.trim()?.takeIf(String::isNotBlank),
            usageKind?.trim()?.takeIf(String::isNotBlank),
        )
        return openReadConnection(databaseFile).use { connection ->
            val (where, args) = whereClause(filter)
            val sql = "SELECT * FROM token_usage_record WHERE $where ORDER BY occurred_at DESC, id DESC LIMIT ?"
            connection.prepareStatement(sql).use { statement ->
                bind(statement, args)
                statement.setInt(args.size + 1, limit.coerceAtMost(100))
                statement.executeQuery().use { results ->
                    buildList { while (results.next()) add(results.toTokenUsageRecord()) }
                }
            }
        }
    }

    /** Best-effort access for the command during a partial startup. */
    fun hasAny(startDate: String, endDate: String = LocalDate.now(zone).format(dateFmt)): Boolean {
        if (!initialized) return false
        return openReadConnection(databaseFile).use { connection ->
            val (where, args) = whereClause(Filter(startDate, endDate, null, null, null, false, null, null))
            connection.prepareStatement("SELECT 1 FROM token_usage_record WHERE $where LIMIT 1").use { statement ->
                bind(statement, args)
                statement.executeQuery().use(ResultSet::next)
            }
        }
    }

    private data class Filter(
        val startDate: String?,
        val endDate: String?,
        val botId: Long?,
        val userId: Long?,
        val groupId: Long?,
        val privateOnly: Boolean,
        val model: String?,
        val usageKind: String?,
    )

    private data class Aggregate(
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long,
        val cachedTokens: Long,
        val callCount: Int,
        val activeUsers: Int,
    )

    private data class UserUsageAggregate(
        val userId: Long,
        val usageKind: String,
        val unit: String,
        val totalUnits: Long,
        val callCount: Int,
    )

    private data class LegacyImportState(
        val occurredAt: Long,
        val date: String,
        val userId: Long,
        val userNickname: String,
        val groupId: Long?,
        val groupName: String?,
        val inputUnits: Long,
        val outputUnits: Long,
        val totalUnits: Long,
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long,
        val cachedTokens: Long,
        val callCount: Int,
    )

    private fun queryAggregate(connection: Connection, filter: Filter, tokenOnly: Boolean): Aggregate {
        val (where, args) = whereClause(filter, tokenOnly = tokenOnly)
        val sql = """
            SELECT COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(completion_tokens), 0),
                   COALESCE(SUM(total_tokens), 0), COALESCE(SUM(cached_tokens), 0),
                   COALESCE(SUM(call_count), 0),
                   COUNT(DISTINCT CASE WHEN user_id > 0 THEN user_id END)
            FROM token_usage_record WHERE $where
        """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeQuery().use { results ->
                check(results.next())
                Aggregate(
                    promptTokens = results.getLong(1),
                    completionTokens = results.getLong(2),
                    totalTokens = results.getLong(3),
                    cachedTokens = results.getLong(4),
                    callCount = results.getLong(5).toInt(),
                    activeUsers = results.getInt(6),
                )
            }
        }
    }

    private fun queryTodayTotal(connection: Connection, filter: Filter): Long {
        val today = LocalDate.now(zone).format(dateFmt)
        if (filter.startDate != null && today < filter.startDate || filter.endDate != null && today > filter.endDate) return 0
        val todayFilter = filter.copy(startDate = today, endDate = today)
        val (where, args) = whereClause(todayFilter, tokenOnly = true)
        return connection.prepareStatement("SELECT COALESCE(SUM(total_tokens), 0) FROM token_usage_record WHERE $where")
            .use { statement ->
                bind(statement, args)
                statement.executeQuery().use { results -> check(results.next()); results.getLong(1) }
            }
    }

    private fun queryDaily(connection: Connection, filter: Filter): List<TokenUsageDailyTotal> {
        val (where, args) = whereClause(filter, tokenOnly = true)
        return connection.prepareStatement(
            "SELECT usage_date, SUM(total_tokens) FROM token_usage_record WHERE $where GROUP BY usage_date ORDER BY usage_date"
        ).use { statement ->
            bind(statement, args)
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) add(TokenUsageDailyTotal(results.getString(1), results.getLong(2)))
                }
            }
        }
    }

    private fun queryTopUsers(
        connection: Connection,
        filter: Filter,
        limit: Int,
    ): List<TokenUsageRanking> {
        val (where, args) = whereClause(filter, tokenOnly = true)
        val totals = connection.prepareStatement(
            "SELECT user_id, SUM(total_tokens) AS total FROM token_usage_record " +
                    "WHERE $where AND user_id > 0 GROUP BY user_id ORDER BY total DESC LIMIT ?"
        ).use { statement ->
            bind(statement, args)
            statement.setInt(args.size + 1, limit)
            statement.executeQuery().use { results ->
                buildList { while (results.next()) add(results.getLong("user_id") to results.getLong("total")) }
            }
        }
        val (nameWhere, nameArgs) = whereClause(filter.copy(model = null, usageKind = null))
        return totals.map { (id, total) ->
            val name = connection.prepareStatement(
                "SELECT user_nickname FROM token_usage_record WHERE $nameWhere AND user_id = ? " +
                        "AND user_nickname <> '' ORDER BY occurred_at DESC, id DESC LIMIT 1"
            ).use { statement ->
                bind(statement, nameArgs)
                statement.setLong(nameArgs.size + 1, id)
                statement.executeQuery().use { results -> if (results.next()) results.getString(1).orEmpty() else "" }
            }
            TokenUsageRanking(id, name, total)
        }
    }

    private fun queryTopGroups(
        connection: Connection,
        filter: Filter,
        limit: Int,
    ): List<TokenUsageRanking> {
        val (where, args) = whereClause(filter, requireGroup = true, tokenOnly = true)
        val totals = connection.prepareStatement(
            "SELECT group_id, SUM(total_tokens) AS total FROM token_usage_record WHERE $where GROUP BY group_id ORDER BY total DESC LIMIT ?"
        ).use { statement ->
            bind(statement, args)
            statement.setInt(args.size + 1, limit)
            statement.executeQuery().use { results ->
                buildList { while (results.next()) add(results.getLong("group_id") to results.getLong("total")) }
            }
        }
        val (nameWhere, nameArgs) = whereClause(
            filter.copy(model = null, usageKind = null),
            requireGroup = true,
        )
        return totals.map { (id, total) ->
            val name = connection.prepareStatement(
                "SELECT group_name FROM token_usage_record WHERE $nameWhere AND group_id = ? " +
                        "AND group_name IS NOT NULL AND group_name <> '' ORDER BY occurred_at DESC, id DESC LIMIT 1"
            ).use { statement ->
                bind(statement, nameArgs)
                statement.setLong(nameArgs.size + 1, id)
                statement.executeQuery().use { results -> if (results.next()) results.getString(1).orEmpty() else "" }
            }
            TokenUsageRanking(id, name, total)
        }
    }

    private fun queryModels(
        connection: Connection,
        filter: Filter,
        limit: Int,
    ): List<TokenUsageModelTotal> {
        val (where, args) = whereClause(filter, tokenOnly = true)
        return connection.prepareStatement(
            """
            SELECT COALESCE(provider, ''), COALESCE(model, ''), SUM(total_units), SUM(call_count)
            FROM token_usage_record WHERE $where
            GROUP BY provider, model ORDER BY SUM(total_tokens) DESC LIMIT ?
            """.trimIndent()
        ).use { statement ->
            bind(statement, args)
            statement.setInt(args.size + 1, limit)
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) {
                        add(
                            TokenUsageModelTotal(
                                provider = results.getString(1).ifBlank { "unknown" },
                                model = results.getString(2).ifBlank { "unknown" },
                                totalTokens = results.getLong(3),
                                callCount = results.getLong(4).toInt(),
                            )
                        )
                    }
                }
            }
        }
    }

    private fun queryBreakdown(
        connection: Connection,
        filter: Filter,
        limit: Int,
    ): List<TokenUsageBreakdown> {
        val (where, args) = whereClause(filter)
        return connection.prepareStatement(
            """
            SELECT COALESCE(provider, ''), COALESCE(model, ''),
                   COALESCE(usage_kind, 'other'), COALESCE(unit, 'units'),
                   SUM(input_units), SUM(output_units), SUM(total_units), SUM(call_count)
            FROM token_usage_record WHERE $where
            GROUP BY provider, model, usage_kind, unit
            ORDER BY SUM(total_units) DESC LIMIT ?
            """.trimIndent()
        ).use { statement ->
            bind(statement, args)
            statement.setInt(args.size + 1, limit)
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) {
                        add(
                            TokenUsageBreakdown(
                                provider = results.getString(1).ifBlank { "unknown" },
                                model = results.getString(2).ifBlank { "unknown" },
                                usageKind = results.getString(3).ifBlank { "other" },
                                unit = results.getString(4).ifBlank { "units" },
                                inputUnits = results.getLong(5),
                                outputUnits = results.getLong(6),
                                totalUnits = results.getLong(7),
                                callCount = results.getLong(8).toInt(),
                            )
                        )
                    }
                }
            }
        }
    }

    private fun queryUserUsage(
        connection: Connection,
        filter: Filter,
        limit: Int,
    ): List<ModelUsageUserTotal> {
        val (where, args) = whereClause(filter)
        val totals = connection.prepareStatement(
            """
            SELECT user_id, COALESCE(usage_kind, 'other'), COALESCE(unit, 'units'),
                   SUM(total_units), SUM(call_count)
            FROM token_usage_record
            WHERE $where AND user_id > 0
            GROUP BY user_id, usage_kind, unit
            ORDER BY usage_kind, unit, SUM(total_units) DESC
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            bind(statement, args)
            statement.setInt(args.size + 1, limit)
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) {
                        add(
                            UserUsageAggregate(
                                userId = results.getLong(1),
                                usageKind = results.getString(2),
                                unit = results.getString(3),
                                totalUnits = results.getLong(4),
                                callCount = results.getLong(5).toInt(),
                            )
                        )
                    }
                }
            }
        }
        val (nameWhere, nameArgs) = whereClause(filter.copy(model = null, usageKind = null))
        return totals.map { row ->
            val name = connection.prepareStatement(
                "SELECT user_nickname FROM token_usage_record WHERE $nameWhere AND user_id = ? AND user_nickname <> '' " +
                        "ORDER BY occurred_at DESC, id DESC LIMIT 1"
            ).use { statement ->
                bind(statement, nameArgs)
                statement.setLong(nameArgs.size + 1, row.userId)
                statement.executeQuery().use { results -> if (results.next()) results.getString(1).orEmpty() else "" }
            }
            ModelUsageUserTotal(
                userId = row.userId,
                name = name,
                usageKind = row.usageKind,
                unit = row.unit,
                totalUnits = row.totalUnits,
                callCount = row.callCount,
            )
        }
    }

    private fun queryUsageDaily(
        connection: Connection,
        filter: Filter,
    ): List<ModelUsageDailyTotal> {
        val (where, args) = whereClause(filter)
        return connection.prepareStatement(
            """
            SELECT usage_date, COALESCE(usage_kind, 'other'), COALESCE(unit, 'units'),
                   SUM(total_units), SUM(call_count)
            FROM token_usage_record
            WHERE $where
            GROUP BY usage_date, usage_kind, unit
            ORDER BY usage_date, usage_kind, unit
            """.trimIndent()
        ).use { statement ->
            bind(statement, args)
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) {
                        add(
                            ModelUsageDailyTotal(
                                date = results.getString(1),
                                usageKind = results.getString(2),
                                unit = results.getString(3),
                                totalUnits = results.getLong(4),
                                callCount = results.getLong(5).toInt(),
                            )
                        )
                    }
                }
            }
        }
    }

    private fun whereClause(
        filter: Filter,
        requireGroup: Boolean = false,
        tokenOnly: Boolean = false,
    ): Pair<String, List<Any>> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (tokenOnly) conditions += "unit = 'tokens'"
        filter.startDate?.let { conditions += "usage_date >= ?"; args += it }
        filter.endDate?.let { conditions += "usage_date <= ?"; args += it }
        filter.botId?.let { conditions += "bot_id = ?"; args += it }
        filter.userId?.let { conditions += "user_id = ?"; args += it }
        if (requireGroup) conditions += "group_id IS NOT NULL"
        if (filter.privateOnly) conditions += "group_id IS NULL"
        filter.groupId?.let { conditions += "group_id = ?"; args += it }
        filter.model?.let { conditions += "model = ?"; args += it }
        filter.usageKind?.let { conditions += "usage_kind = ?"; args += it }
        return (conditions.takeIf { it.isNotEmpty() }?.joinToString(" AND ") ?: "1 = 1") to args
    }

    private fun bind(statement: java.sql.PreparedStatement, args: List<Any>) {
        args.forEachIndexed { index, value ->
            when (value) {
                is Long -> statement.setLong(index + 1, value)
                is Int -> statement.setInt(index + 1, value)
                else -> statement.setString(index + 1, value.toString())
            }
        }
    }

    private fun importLegacyJson(file: File) {
        if (!file.isFile || file.length() == 0L) return
        val fingerprint = runCatching { sha256(file) }.getOrElse { cause ->
            warn("计算旧 Token 使用文件指纹失败，已跳过迁移", cause)
            return
        }
        val fingerprintMatches = runCatching {
            withWriteConnection { connection -> readMeta(connection, LEGACY_FINGERPRINT_KEY) == fingerprint }
        }.getOrElse { cause ->
            warn("读取旧 Token 迁移状态失败，将继续校验现有数据", cause)
            false
        }
        if (fingerprintMatches) return

        val records = try {
            json.decodeFromString(legacySerializer, file.readText())
        } catch (cause: Throwable) {
            val backup = File(file.parentFile, "${file.name}.broken-${System.currentTimeMillis()}")
            runCatching { file.copyTo(backup, overwrite = true) }
            warn("读取旧 Token 使用文件失败，已保留原文件并跳过迁移", cause)
            return
        }
        if (records.isEmpty()) return
        runCatching {
            var imported = false
            withWriteConnection { connection ->
                val oldAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    if (!legacyRowsMatch(connection, records)) {
                        connection.prepareStatement(
                            """
                            INSERT INTO token_usage_record(
                                occurred_at, usage_date, user_id, user_nickname, group_id, group_name,
                                usage_kind, unit, input_units, output_units, total_units,
                                prompt_tokens, completion_tokens, total_tokens, cached_tokens,
                                call_count, detailed, legacy_key
                            ) VALUES (?, ?, ?, ?, ?, ?, 'chat', 'tokens', ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                            ON CONFLICT(legacy_key) DO UPDATE SET
                                occurred_at = excluded.occurred_at,
                                usage_date = excluded.usage_date,
                                user_nickname = excluded.user_nickname,
                                group_name = excluded.group_name,
                                usage_kind = excluded.usage_kind,
                                unit = excluded.unit,
                                input_units = excluded.input_units,
                                output_units = excluded.output_units,
                                total_units = excluded.total_units,
                                prompt_tokens = excluded.prompt_tokens,
                                completion_tokens = excluded.completion_tokens,
                                total_tokens = excluded.total_tokens,
                                cached_tokens = excluded.cached_tokens,
                                call_count = excluded.call_count
                            """.trimIndent()
                        ).use { statement ->
                            records.forEach { record ->
                                val state = record.toLegacyImportState()
                                statement.setLong(1, state.occurredAt)
                                statement.setString(2, state.date)
                                statement.setLong(3, state.userId)
                                statement.setString(4, state.userNickname)
                                if (state.groupId == null) statement.setNull(5, java.sql.Types.INTEGER)
                                else statement.setLong(5, state.groupId)
                                if (state.groupName == null) statement.setNull(6, java.sql.Types.VARCHAR)
                                else statement.setString(6, state.groupName)
                                statement.setLong(7, state.inputUnits)
                                statement.setLong(8, state.outputUnits)
                                statement.setLong(9, state.totalUnits)
                                statement.setLong(10, state.promptTokens)
                                statement.setLong(11, state.completionTokens)
                                statement.setLong(12, state.totalTokens)
                                statement.setLong(13, state.cachedTokens)
                                statement.setInt(14, state.callCount)
                                statement.setString(15, legacyKey(record))
                                statement.addBatch()
                            }
                            statement.executeBatch()
                        }
                        imported = true
                    }
                    writeMeta(connection, LEGACY_FINGERPRINT_KEY, fingerprint)
                    connection.commit()
                } catch (cause: Throwable) {
                    connection.rollback()
                    throw cause
                } finally {
                    connection.autoCommit = oldAutoCommit
                }
            }
            if (imported) warn("已将 ${records.size} 条旧 Token 聚合记录迁移到 SQLite", null)
        }.onFailure { warn("迁移旧 Token 使用记录失败，保留原 JSON 以便下次重试", it) }
    }

    private fun legacyRowsMatch(connection: Connection, records: List<TokenUsageDailyRecord>): Boolean {
        val expected = records.associate { legacyKey(it) to it.toLegacyImportState() }
        val actual = connection.prepareStatement(
            """
            SELECT legacy_key, occurred_at, usage_date, user_id, user_nickname, group_id, group_name,
                   input_units, output_units, total_units, prompt_tokens, completion_tokens,
                   total_tokens, cached_tokens, call_count
            FROM token_usage_record WHERE legacy_key IS NOT NULL
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { results ->
                buildMap {
                    while (results.next()) {
                        put(
                            results.getString("legacy_key"),
                            LegacyImportState(
                                occurredAt = results.getLong("occurred_at"),
                                date = results.getString("usage_date"),
                                userId = results.getLong("user_id"),
                                userNickname = results.getString("user_nickname").orEmpty(),
                                groupId = results.getLong("group_id").takeUnless { results.wasNull() },
                                groupName = results.getString("group_name"),
                                inputUnits = results.getLong("input_units"),
                                outputUnits = results.getLong("output_units"),
                                totalUnits = results.getLong("total_units"),
                                promptTokens = results.getLong("prompt_tokens"),
                                completionTokens = results.getLong("completion_tokens"),
                                totalTokens = results.getLong("total_tokens"),
                                cachedTokens = results.getLong("cached_tokens"),
                                callCount = results.getInt("call_count"),
                            )
                        )
                    }
                }
            }
        }
        return actual == expected
    }

    private fun TokenUsageDailyRecord.toLegacyImportState(): LegacyImportState = LegacyImportState(
        occurredAt = LocalDate.parse(date, dateFmt).atStartOfDay(zone).toEpochSecond(),
        date = date,
        userId = userId,
        userNickname = sanitizeNickname(userNickname),
        groupId = groupId,
        groupName = groupName?.let(::sanitizeNickname),
        inputUnits = promptTokens.coerceAtLeast(0),
        outputUnits = completionTokens.coerceAtLeast(0),
        totalUnits = totalTokens.coerceAtLeast(0),
        promptTokens = promptTokens.coerceAtLeast(0),
        completionTokens = completionTokens.coerceAtLeast(0),
        totalTokens = totalTokens.coerceAtLeast(0),
        cachedTokens = cachedTokens.coerceAtLeast(0),
        callCount = callCount.coerceAtLeast(0),
    )

    private fun readMeta(connection: Connection, key: String): String? =
        connection.prepareStatement("SELECT value FROM token_usage_meta WHERE key = ?").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { results -> if (results.next()) results.getString(1) else null }
        }

    private fun writeMeta(connection: Connection, key: String, value: String) {
        connection.prepareStatement(
            "INSERT INTO token_usage_meta(key, value) VALUES (?, ?) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value"
        ).use { statement ->
            statement.setString(1, key)
            statement.setString(2, value)
            statement.executeUpdate()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hex = "0123456789abcdef"
        return buildString(64) {
            digest.digest().forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }

    private fun legacyKey(record: TokenUsageDailyRecord): String =
        listOf(record.date, record.userId, record.groupId ?: "private").joinToString("|")

    private fun dateFor(timestamp: Long): String =
        LocalDate.ofInstant(Instant.ofEpochSecond(timestamp), zone).format(dateFmt)

    private fun providerFor(apiBaseUrl: String?): String? {
        val host = runCatching { apiBaseUrl?.trim()?.let(java.net.URI::create)?.host?.lowercase() }.getOrNull() ?: return null
        return when {
            host == "api.deepseek.com" || host.endsWith(".deepseek.com") -> "deepseek"
            else -> host
        }
    }

    private fun sanitizeNickname(s: String): String {
        if (s.isEmpty()) return s
        return buildString(s.length) {
            for (c in s) {
                if (c == ' ' || (!c.isISOControl() && c.category != CharCategory.FORMAT)) append(c)
                else append(' ')
            }
        }.trim().replace(Regex(" {2,}"), " ")
    }

    private fun <T> withWriteConnection(block: (Connection) -> T): T {
        synchronized(writeLock) {
            check(initialized) { "Token SQLite 尚未初始化" }
            val connection = writeConnection?.takeUnless(Connection::isClosed)
                ?: openConnection(databaseFile).also {
                    configureWriteConnection(it)
                    writeConnection = it
                }
            return block(connection)
        }
    }

    private fun createSchema(connection: Connection) {
        val oldAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS token_usage_record(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        occurred_at INTEGER NOT NULL,
                        usage_date TEXT NOT NULL,
                        bot_id INTEGER,
                        user_id INTEGER NOT NULL,
                        user_nickname TEXT NOT NULL DEFAULT '',
                        group_id INTEGER,
                        group_name TEXT,
                        endpoint_label TEXT,
                        model_alias TEXT,
                        provider TEXT,
                        model TEXT,
                        usage_kind TEXT NOT NULL DEFAULT 'chat',
                        unit TEXT NOT NULL DEFAULT 'tokens',
                        input_units INTEGER NOT NULL DEFAULT 0,
                        output_units INTEGER NOT NULL DEFAULT 0,
                        total_units INTEGER NOT NULL DEFAULT 0,
                        prompt_tokens INTEGER NOT NULL DEFAULT 0,
                        completion_tokens INTEGER NOT NULL DEFAULT 0,
                        total_tokens INTEGER NOT NULL DEFAULT 0,
                        cached_tokens INTEGER NOT NULL DEFAULT 0,
                        call_count INTEGER NOT NULL DEFAULT 1,
                        detailed INTEGER NOT NULL DEFAULT 1,
                        legacy_key TEXT UNIQUE
                    )
                    """.trimIndent()
                )
                ensureColumn(connection, "token_usage_record", "model_alias", "TEXT")
                ensureColumn(connection, "token_usage_record", "usage_kind", "TEXT NOT NULL DEFAULT 'chat'")
                ensureColumn(connection, "token_usage_record", "unit", "TEXT NOT NULL DEFAULT 'tokens'")
                ensureColumn(connection, "token_usage_record", "input_units", "INTEGER NOT NULL DEFAULT 0")
                ensureColumn(connection, "token_usage_record", "output_units", "INTEGER NOT NULL DEFAULT 0")
                ensureColumn(connection, "token_usage_record", "total_units", "INTEGER NOT NULL DEFAULT 0")
                statement.executeUpdate(
                    """
                    UPDATE token_usage_record
                    SET input_units = prompt_tokens,
                        output_units = completion_tokens,
                        total_units = total_tokens
                    WHERE unit = 'tokens'
                      AND input_units = 0 AND output_units = 0 AND total_units = 0
                    """.trimIndent()
                )
                statement.executeUpdate(
                    "UPDATE token_usage_record SET usage_kind = 'chat' WHERE usage_kind = 'tokens'"
                )
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_token_usage_date ON token_usage_record(usage_date)")
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_token_usage_user_date ON token_usage_record(user_id, usage_date)")
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_token_usage_group_date ON token_usage_record(group_id, usage_date)")
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_token_usage_model_date ON token_usage_record(model, usage_date)")
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_token_usage_kind_date ON token_usage_record(usage_kind, unit, usage_date)"
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS token_usage_meta(
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
            connection.prepareStatement(
                "INSERT INTO token_usage_meta(key, value) VALUES ('schema_version', ?) " +
                        "ON CONFLICT(key) DO UPDATE SET value = excluded.value"
            ).use { statement ->
                statement.setString(1, SCHEMA_VERSION.toString())
                statement.executeUpdate()
            }
            connection.commit()
        } catch (cause: Throwable) {
            connection.rollback()
            throw cause
        } finally {
            connection.autoCommit = oldAutoCommit
        }
    }

    private fun ensureColumn(
        connection: Connection,
        table: String,
        column: String,
        definition: String,
    ) {
        val exists = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { results ->
                generateSequence { if (results.next()) results.getString("name") else null }
                    .any { it == column }
            }
        }
        if (!exists) {
            connection.createStatement().use { statement ->
                statement.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition")
            }
        }
    }

    private fun openConnection(databaseFile: File): Connection =
        DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}")

    private fun openReadConnection(databaseFile: File): Connection {
        val config = SQLiteConfig().apply {
            setReadOnly(true)
            setBusyTimeout(BUSY_TIMEOUT_MS)
        }
        return DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}", config.toProperties()).also {
            it.createStatement().use { statement -> statement.execute("PRAGMA query_only=ON") }
        }
    }

    private fun configureWriteConnection(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA synchronous=NORMAL")
            statement.execute("PRAGMA busy_timeout=$BUSY_TIMEOUT_MS")
            statement.execute("PRAGMA wal_autocheckpoint=1000")
        }
    }

    private fun ResultSet.toTokenUsageRecord(): TokenUsageRecord = TokenUsageRecord(
        id = getLong("id"),
        timestamp = getLong("occurred_at"),
        date = getString("usage_date"),
        botId = getLong("bot_id").takeUnless { wasNull() },
        userId = getLong("user_id"),
        userNickname = getString("user_nickname").orEmpty(),
        groupId = getLong("group_id").takeUnless { wasNull() },
        groupName = getString("group_name"),
        endpointLabel = getString("endpoint_label"),
        modelAlias = getString("model_alias"),
        provider = getString("provider"),
        model = getString("model"),
        usageKind = getString("usage_kind") ?: "chat",
        unit = getString("unit") ?: "tokens",
        inputUnits = getLong("input_units"),
        outputUnits = getLong("output_units"),
        totalUnits = getLong("total_units"),
        promptTokens = getLong("prompt_tokens"),
        completionTokens = getLong("completion_tokens"),
        totalTokens = getLong("total_tokens"),
        cachedTokens = getLong("cached_tokens"),
        callCount = getInt("call_count"),
        detailed = getInt("detailed") != 0,
    )

    private fun warn(message: String, cause: Throwable?) {
        warningLogger?.invoke(message, cause)
    }
}
