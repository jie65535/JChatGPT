package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageEvent
import top.jie65535.mirai.config.ModelConfig
import top.jie65535.mirai.config.ModelDefinition
import top.jie65535.mirai.config.ModelProviderDefinition
import top.jie65535.mirai.config.PluginConfig
import top.jie65535.mirai.data.TokenUsageRecord
import top.jie65535.mirai.data.TokenUsageStore
import top.jie65535.mirai.data.TokenUsageSummary
import top.jie65535.mirai.llm.ModelCatalog
import java.net.URI
import java.time.LocalDate
import java.time.ZoneId

class QueryTokenUsageAgent : BaseAgent(
    tool = Tool.function(
        name = "queryTokenUsage",
        description = "查询当前会话的模型用量或提供商余额。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("operation") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("summary")
                        add("details")
                        add("balance")
                        add("overview")
                    }
                    put("description", "summary用量，details明细，balance余额，overview为用量加余额")
                }
                putJsonObject("days") {
                    put("type", "integer")
                    put("description", "统计最近多少天，包含今天，默认7，范围1到3650")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "排名或最近明细条数，默认20，最多100")
                }
                putJsonObject("userId") {
                    put("type", "integer")
                    put("description", "群聊中可选，仅统计当前群内指定用户QQ号；私聊中忽略此参数并固定为当前私聊对象")
                }
                putJsonObject("usageType") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("chat")
                        add("profile")
                        add("reasoning")
                        add("visual")
                        add("web_summary")
                        add("image")
                        add("tts")
                    }
                    put("description", "可选，按模型用途筛选")
                }
            }
        }
    )
) {
    companion object {
        private const val MAX_DAYS = 3650
        private const val MAX_DETAILS = 100
        private const val DEEPSEEK_BALANCE_URL = "https://api.deepseek.com/user/balance"
        private val OPERATIONS = setOf("summary", "details", "balance", "overview")
        private val USAGE_TYPES = setOf("chat", "profile", "reasoning", "visual", "web_summary", "image", "tts")
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        internal data class QueryScope(
            val botId: Long,
            val userId: Long?,
            val groupId: Long?,
            val privateOnly: Boolean,
        )

        internal data class BalanceAccount(
            val name: String,
            val api: String,
            val token: String,
        )

        internal fun queryScope(
            botId: Long,
            senderId: Long,
            currentGroupId: Long?,
            requestedUserId: Long?,
        ): QueryScope = if (currentGroupId == null) {
            QueryScope(botId, senderId, null, privateOnly = true)
        } else {
            QueryScope(botId, requestedUserId, currentGroupId, privateOnly = false)
        }

        internal fun isDeepSeekApi(api: String): Boolean {
            val host = runCatching { URI.create(api.trim()).host?.lowercase() }.getOrNull() ?: return false
            return host == "api.deepseek.com" || host.endsWith(".deepseek.com")
        }

        internal fun parseDeepSeekBalance(body: String): JsonObject {
            val root = json.parseToJsonElement(body).jsonObject
            return buildJsonObject {
                put("available", root["is_available"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false)
                putJsonArray("balances") {
                    root["balance_infos"]?.jsonArray?.forEach { element ->
                        val info = element.jsonObject
                        addJsonObject {
                            put("currency", info["currency"]?.jsonPrimitive?.contentOrNull.orEmpty())
                            put("total", info["total_balance"]?.jsonPrimitive?.contentOrNull.orEmpty())
                            put("granted", info["granted_balance"]?.jsonPrimitive?.contentOrNull.orEmpty())
                            put("toppedUp", info["topped_up_balance"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        }
                    }
                }
            }
        }

        internal fun collectDeepSeekBalanceAccounts(
            providers: List<ModelProviderDefinition>,
            models: List<ModelDefinition>,
            legacyAccounts: List<BalanceAccount> = emptyList(),
        ): List<BalanceAccount> {
            val referencedProviders = models.mapTo(HashSet()) { it.provider.trim() }
            val accounts = providers.asSequence()
                .filter { it.name.trim() in referencedProviders }
                .filter { it.token.isNotBlank() && isDeepSeekApi(it.api) }
                .map { BalanceAccount(it.name.trim().ifBlank { "deepseek" }, it.api.trim(), it.token.trim()) }
                .toMutableList()
            accounts += legacyAccounts.filter { it.token.isNotBlank() && isDeepSeekApi(it.api) }
                .map { it.copy(api = it.api.trim(), token = it.token.trim()) }
            return accounts.distinctBy { it.token }
        }
    }

    override val isEnabled: Boolean
        get() = TokenUsageStore.isAvailable ||
                configuredDeepSeekAccounts().isNotEmpty()

    override val loadingMessage: String
        get() = "查询模型用量中..."

    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        val operation = args?.get("operation")?.jsonPrimitive?.contentOrNull?.lowercase() ?: "summary"
        require(operation in OPERATIONS) { "不支持的模型用量查询操作：$operation" }
        val days = (args?.get("days")?.jsonPrimitive?.intOrNull ?: 7).coerceIn(1, MAX_DAYS)
        val limit = (args?.get("limit")?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, MAX_DETAILS)
        val requestedUserId = args?.get("userId")?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
        val usageType = args?.get("usageType")?.jsonPrimitive?.contentOrNull?.lowercase()
        require(usageType == null || usageType in USAGE_TYPES) { "不支持的模型用途：$usageType" }
        val scope = queryScope(
            botId = event.bot.id,
            senderId = event.sender.id,
            currentGroupId = (event as? GroupMessageEvent)?.group?.id,
            requestedUserId = requestedUserId,
        )

        val result = buildJsonObject {
            put("operation", operation)
            if (operation == "summary" || operation == "details" || operation == "overview") {
                if (!TokenUsageStore.isAvailable) {
                    put("usageError", "模型用量 SQLite 尚未初始化")
                } else {
                    val startDate = LocalDate.now(ZoneId.systemDefault()).minusDays((days - 1).toLong()).toString()
                    val summary = TokenUsageStore.summary(
                        startDate = startDate,
                        botId = scope.botId,
                        userId = scope.userId,
                        groupId = scope.groupId,
                        privateOnly = scope.privateOnly,
                        usageKind = usageType,
                        rankingLimit = limit,
                    )
                    putJsonObject("usage") { writeSummary(summary, days) }
                    if (operation == "details") {
                        putJsonArray("details") {
                            TokenUsageStore.recent(
                                limit = limit,
                                startDate = startDate,
                                botId = scope.botId,
                                userId = scope.userId,
                                groupId = scope.groupId,
                                privateOnly = scope.privateOnly,
                                usageKind = usageType,
                            ).forEach { record -> addRecord(record) }
                        }
                    }
                }
            }
            if (operation == "balance" || operation == "overview") {
                put("balance", queryBalances())
            }
        }
        return result.toString()
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.writeSummary(summary: TokenUsageSummary, days: Int) {
        put("days", days)
        put("promptTokens", summary.promptTokens)
        put("completionTokens", summary.completionTokens)
        put("totalTokens", summary.totalTokens)
        put("cachedTokens", summary.cachedTokens)
        put("tokenCallCount", summary.callCount)
        put("allCallCount", summary.allCallCount)
        put("activeUsers", summary.activeUsers)
        put("allActiveUsers", summary.allActiveUsers)
        put("todayTotalTokens", summary.todayTotal)
        put("cacheHitRatePercent", if (summary.promptTokens > 0) summary.cachedTokens * 100.0 / summary.promptTokens else 0.0)
        putJsonArray("daily") {
            summary.daily.forEach { daily ->
                addJsonObject {
                    put("date", daily.date)
                    put("totalTokens", daily.totalTokens)
                }
            }
        }
        putJsonArray("usageDaily") {
            summary.usageDaily.forEach { daily ->
                addJsonObject {
                    put("date", daily.date)
                    put("usageKind", daily.usageKind)
                    put("unit", daily.unit)
                    put("totalUnits", daily.totalUnits)
                    put("callCount", daily.callCount)
                }
            }
        }
        putJsonArray("models") {
            summary.models.forEach { model ->
                addJsonObject {
                    put("provider", model.provider)
                    put("model", model.model)
                    put("totalTokens", model.totalTokens)
                    put("callCount", model.callCount)
                }
            }
        }
        putJsonArray("breakdown") {
            summary.breakdown.forEach { item ->
                addJsonObject {
                    put("provider", item.provider)
                    put("model", item.model)
                    put("usageKind", item.usageKind)
                    put("unit", item.unit)
                    put("inputUnits", item.inputUnits)
                    put("outputUnits", item.outputUnits)
                    put("totalUnits", item.totalUnits)
                    put("callCount", item.callCount)
                }
            }
        }
        putJsonArray("userUsage") {
            summary.userUsage.forEach { item ->
                addJsonObject {
                    put("userId", item.userId)
                    put("name", item.name)
                    put("usageKind", item.usageKind)
                    put("unit", item.unit)
                    put("totalUnits", item.totalUnits)
                    put("callCount", item.callCount)
                }
            }
        }
        putJsonArray("topUsers") {
            summary.topUsers.forEach { ranking ->
                addJsonObject {
                    put("userId", ranking.id)
                    put("name", ranking.name)
                    put("totalTokens", ranking.totalTokens)
                }
            }
        }
        putJsonArray("topGroups") {
            summary.topGroups.forEach { ranking ->
                addJsonObject {
                    put("groupId", ranking.id)
                    put("name", ranking.name)
                    put("totalTokens", ranking.totalTokens)
                }
            }
        }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addRecord(record: TokenUsageRecord) {
        addJsonObject {
            put("date", record.date)
            put("timestamp", record.timestamp)
            put("userId", record.userId)
            put("userName", record.userNickname)
            record.groupId?.let { put("groupId", it) }
            record.groupName?.let { put("groupName", it) }
            record.provider?.let { put("provider", it) }
            record.model?.let { put("model", it) }
            put("usageKind", record.usageKind)
            put("unit", record.unit)
            put("inputUnits", record.inputUnits)
            put("outputUnits", record.outputUnits)
            put("totalUnits", record.totalUnits)
            put("promptTokens", record.promptTokens)
            put("completionTokens", record.completionTokens)
            put("totalTokens", record.totalTokens)
            put("cachedTokens", record.cachedTokens)
            put("callCount", record.callCount)
            put("detailed", record.detailed)
        }
    }

    private fun configuredDeepSeekAccounts(): List<BalanceAccount> {
        val openAiTypes = setOf("openai", "openai-compatible", "openai_compatible")
        fun resolveOpenAiAlias(alias: String) = ModelCatalog.resolve(alias)?.takeIf {
            it.providerType in openAiTypes && it.api.isNotBlank() && it.token.isNotBlank() && it.model.isNotBlank()
        }
        val boundAliases = buildSet {
            add(PluginConfig.chatModelAlias)
            addAll(PluginConfig.chatFallbackModelAliases)
            add(PluginConfig.profileModelAlias.ifBlank { PluginConfig.chatModelAlias })
            add(PluginConfig.reasoningModelAlias)
            add(PluginConfig.visualModelAlias)
            add(PluginConfig.webSummaryModelAlias)
        }.mapTo(HashSet(), String::trim).filterTo(HashSet(), String::isNotEmpty)
        val boundModels = boundAliases.mapNotNull { alias ->
            resolveOpenAiAlias(alias)?.let { definition ->
                ModelDefinition(
                    name = definition.alias,
                    provider = definition.provider,
                    model = definition.model,
                )
            }
        }
        val legacyAccounts = buildList {
            val primaryResolved = resolveOpenAiAlias(PluginConfig.chatModelAlias) != null
            if (!primaryResolved) {
                add(BalanceAccount("legacy-chat", PluginConfig.openAiApi, PluginConfig.openAiToken))
                PluginConfig.chatFallbacks.forEachIndexed { index, fallback ->
                    add(
                        BalanceAccount(
                            name = "legacy-chat-fallback-${index + 1}",
                            api = fallback.api.ifBlank { PluginConfig.openAiApi },
                            token = fallback.token.ifBlank { PluginConfig.openAiToken },
                        )
                    )
                }
            }
            val profileAlias = PluginConfig.profileModelAlias.ifBlank { PluginConfig.chatModelAlias }
            if (PluginConfig.profileEnabled && resolveOpenAiAlias(profileAlias) == null) {
                add(
                    BalanceAccount(
                        "legacy-profile",
                        PluginConfig.profileModelApi.ifBlank { PluginConfig.openAiApi },
                        PluginConfig.profileModelToken.ifBlank { PluginConfig.openAiToken },
                    )
                )
            }
            if (resolveOpenAiAlias(PluginConfig.reasoningModelAlias) == null) {
                add(
                    BalanceAccount(
                        "legacy-reasoning",
                        PluginConfig.reasoningModelApi,
                        PluginConfig.reasoningModelToken,
                    )
                )
            }
            if (resolveOpenAiAlias(PluginConfig.visualModelAlias) == null) {
                add(BalanceAccount("legacy-visual", PluginConfig.visualModelApi, PluginConfig.visualModelToken))
            }
            if (resolveOpenAiAlias(PluginConfig.webSummaryModelAlias) == null) {
                add(
                    BalanceAccount(
                        "legacy-web-summary",
                        PluginConfig.webSummaryModelApi,
                        PluginConfig.webSummaryModelToken,
                    )
                )
            }
        }
        return collectDeepSeekBalanceAccounts(
            providers = ModelConfig.providers,
            models = boundModels,
            legacyAccounts = legacyAccounts,
        )
    }

    private suspend fun queryBalances(): JsonObject {
        val accounts = configuredDeepSeekAccounts()
        if (accounts.isEmpty()) {
            return buildJsonObject {
                put("supported", false)
                put("message", "未配置可查询余额的官方 DeepSeek 账号")
            }
        }
        val results = accounts.map { account -> queryDeepSeekBalance(account) }
        return buildJsonObject {
            put("supported", true)
            putJsonArray("accounts") {
                results.forEach(::add)
            }
        }
    }

    private suspend fun queryDeepSeekBalance(account: BalanceAccount): JsonObject = try {
        val response = httpClient.get(DEEPSEEK_BALANCE_URL) {
            header(HttpHeaders.Authorization, "Bearer ${account.token}")
            timeout {
                requestTimeoutMillis = 20_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 20_000
            }
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            buildJsonObject {
                put("provider", account.name)
                put("service", "deepseek")
                put("error", "HTTP ${response.status.value}")
                put("message", body.take(300))
            }
        } else {
            buildJsonObject {
                put("provider", account.name)
                put("service", "deepseek")
                put("data", parseDeepSeekBalance(body))
            }
        }
    } catch (cause: Throwable) {
        buildJsonObject {
            put("provider", account.name)
            put("service", "deepseek")
            put("error", cause.message ?: cause::class.simpleName.orEmpty())
        }
    }

}
