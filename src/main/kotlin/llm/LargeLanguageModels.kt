package top.jie65535.mirai.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.config.ModelConfig
import top.jie65535.mirai.config.PluginConfig
import kotlin.time.Duration.Companion.milliseconds

object LargeLanguageModels {

    /**
     * 系统提示词
     */
    var systemPrompt: String = "你是一个乐于助人的助手"
        private set

    /**
     * 一个聊天接入点：封装了请求服务、模型名与温度。
     * 主接入点为列表第 0 项，其余为备用接入点，用于容灾切换。
     */
    data class ChatEndpoint(
        val service: ModelService,
        val model: String,
        val temperature: Double?,
        /** 唯一标识，用于健康状态跟踪与日志 */
        val label: String,
        val alias: String = "",
        val provider: String = "",
    )

    data class ProfileEndpoint(
        val service: ModelService,
        val model: String,
        val alias: String = "",
        val provider: String = "",
    )

    data class WebSummaryEndpoint(
        val service: ModelService,
        val model: String,
        val alias: String = "",
        val provider: String = "",
    )

    data class AuxiliaryEndpoint(
        val service: ModelService,
        val model: String,
        val alias: String = "",
        val provider: String = "",
    )

    /**
     * 聊天接入点列表：index 0 为主接入点，其余按配置顺序为备用接入点。
     */
    var chatEndpoints: List<ChatEndpoint> = emptyList()
        private set

    /**
     * 主聊天接入点服务（向后兼容旧用法）。
     */
    val chat: ModelService?
        get() = chatEndpoints.firstOrNull()?.service

    /**
     * 推理模型
     */
    var reasoning: AuxiliaryEndpoint? = null

    /**
     * 视觉模型
     */
    var visual: AuxiliaryEndpoint? = null

    /** 历史用户画像分析模型。 */
    var profile: ProfileEndpoint? = null
        private set

    /** 网页正文提炼模型。 */
    var webSummary: WebSummaryEndpoint? = null
        private set

    /**
     * 接入点健康状态：记录各接入点的冷却截止时间戳（毫秒）。
     * 失败的接入点进入冷却，期间在 [orderedChatEndpoints] 中被排到队尾，
     * 避免每条消息都先卡在故障接入点上白白等一次超时。
     */
    private val cooldownUntil = HashMap<String, Long>()

    /** 上报某接入点调用失败，使其进入冷却。 */
    fun reportFailure(endpoint: ChatEndpoint) {
        val minutes = PluginConfig.fallbackCooldownMinutes
        // 只有存在备用接入点时冷却才有意义；否则没有可切换的目标，标记冷却反而无益
        if (minutes > 0 && chatEndpoints.size > 1) {
            cooldownUntil[endpoint.label] = System.currentTimeMillis() + minutes * 60_000L
        }
    }

    /** 上报某接入点调用成功，清除其冷却。 */
    fun reportSuccess(endpoint: ChatEndpoint) {
        cooldownUntil.remove(endpoint.label)
    }

    /**
     * 返回按健康度排序的接入点：未冷却的保持配置原顺序在前，冷却中的排到后面
     * （冷却中再按剩余冷却时间升序，优先重试快恢复的）。排序稳定，主接入点健康时始终最先。
     */
    fun orderedChatEndpoints(): List<ChatEndpoint> {
        if (chatEndpoints.size <= 1) return chatEndpoints
        val now = System.currentTimeMillis()
        return chatEndpoints.sortedBy { ep ->
            val until = cooldownUntil[ep.label] ?: 0L
            if (until > now) until else 0L
        }
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private fun parseExtraBody(raw: String): JsonObject? {
        if (raw.isBlank()) return null
        return try {
            json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    fun reload() {
        (ModelCatalog.validationIssues() + ModelCatalog.roleValidationIssues())
            .distinct()
            .forEach(JChatGPT.logger::warning)
        val timeout = PluginConfig.timeout.milliseconds
        val firstChunkTimeout = PluginConfig.firstChunkTimeout.milliseconds

        // 初始化聊天接入点（主 + 备用），并重置健康状态
        cooldownUntil.clear()
        val endpoints = mutableListOf<ChatEndpoint>()
        val primaryAlias = PluginConfig.chatModelAlias.trim()
        if (primaryAlias.isNotEmpty()) {
            resolveOpenAi(primaryAlias)?.let { definition ->
                endpoints += ChatEndpoint(
                    service = modelService(definition, timeout, firstChunkTimeout),
                    model = definition.model,
                    temperature = PluginConfig.chatTemperature,
                    label = "primary:$primaryAlias",
                    alias = primaryAlias,
                    provider = definition.provider,
                )
            }
        }
        var legacyPrimaryUsed = false
        if (endpoints.isEmpty() && PluginConfig.openAiApi.isNotBlank() && PluginConfig.openAiToken.isNotBlank()) {
            endpoints += ChatEndpoint(
                service = ModelService(
                    baseUrl = ModelCatalog.normalizeOpenAiApi(PluginConfig.openAiApi),
                    usageLogger = { message -> JChatGPT.logger.info(message) },
                    token = PluginConfig.openAiToken,
                    timeout = timeout,
                    firstChunkTimeout = firstChunkTimeout,
                    extraBody = parseExtraBody(PluginConfig.chatModelExtraBody)
                ),
                model = PluginConfig.chatModel,
                temperature = PluginConfig.chatTemperature,
                label = "primary",
                alias = "legacy-primary",
                provider = providerName(PluginConfig.openAiApi),
            )
            legacyPrimaryUsed = true
        }

        PluginConfig.chatFallbackModelAliases.map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { alias ->
                resolveOpenAi(alias)?.let { definition ->
                    val label = if (endpoints.isEmpty()) {
                        "primary:$alias"
                    } else {
                        "fallback${endpoints.size - 1}:$alias"
                    }
                    endpoints += ChatEndpoint(
                        service = modelService(definition, timeout, firstChunkTimeout),
                        model = definition.model,
                        temperature = PluginConfig.chatTemperature,
                        label = label,
                        alias = alias,
                        provider = definition.provider,
                    )
                }
            }

        if (legacyPrimaryUsed) {
            // 备用接入点：留空字段继承主接入点配置
            PluginConfig.chatFallbacks.forEachIndexed { i, fb ->
                val api = fb.api.ifBlank { PluginConfig.openAiApi }
                val token = fb.token.ifBlank { PluginConfig.openAiToken }
                val model = fb.model.ifBlank { PluginConfig.chatModel }
                val extraBody = fb.extraBody.ifBlank { PluginConfig.chatModelExtraBody }
                if (api.isNotBlank() && token.isNotBlank()) {
                    endpoints += ChatEndpoint(
                        service = ModelService(
                            baseUrl = ModelCatalog.normalizeOpenAiApi(api),
                            usageLogger = { message -> JChatGPT.logger.info(message) },
                            token = token,
                            timeout = timeout,
                            firstChunkTimeout = firstChunkTimeout,
                            extraBody = parseExtraBody(extraBody)
                        ),
                        model = model,
                        temperature = PluginConfig.chatTemperature,
                        label = "fallback${endpoints.size - 1}:legacy-$i:$model",
                        alias = "legacy-fallback$i",
                        provider = providerName(api),
                    )
                }
            }
        }
        chatEndpoints = endpoints

        profile = null
        if (PluginConfig.profileEnabled) {
            val profileAlias = PluginConfig.profileModelAlias.ifBlank { PluginConfig.chatModelAlias }
            val definition = profileAlias.trim().takeIf(String::isNotEmpty)?.let(::resolveOpenAi)
            if (definition != null) {
                val profileFirstChunk = PluginConfig.profileFirstChunkTimeout.milliseconds
                profile = ProfileEndpoint(
                    service = modelService(
                        definition,
                        timeout = maxOf(timeout, profileFirstChunk),
                        firstChunkTimeout = profileFirstChunk,
                        maxConcurrentRequests = PluginConfig.profileMaxConcurrentRequests,
                    ),
                    model = definition.model,
                    alias = definition.alias,
                    provider = definition.provider,
                )
            } else {
                val api = PluginConfig.profileModelApi.ifBlank { PluginConfig.openAiApi }
                val token = PluginConfig.profileModelToken.ifBlank { PluginConfig.openAiToken }
                val model = PluginConfig.profileModel.ifBlank { PluginConfig.chatModel }
                val extraBody = PluginConfig.profileModelExtraBody.ifBlank { PluginConfig.chatModelExtraBody }
                if (api.isNotBlank() && token.isNotBlank() && model.isNotBlank()) {
                    val profileFirstChunk = PluginConfig.profileFirstChunkTimeout.milliseconds
                    profile = ProfileEndpoint(
                        service = ModelService(
                            baseUrl = ModelCatalog.normalizeOpenAiApi(api),
                            usageLogger = { message -> JChatGPT.logger.info(message) },
                            token = token,
                            timeout = maxOf(timeout, profileFirstChunk),
                            firstChunkTimeout = profileFirstChunk,
                            extraBody = parseExtraBody(extraBody),
                            maxConcurrentRequests = PluginConfig.profileMaxConcurrentRequests,
                        ),
                        model = model,
                        alias = "legacy-profile",
                        provider = providerName(api),
                    )
                }
            }
        }

        webSummary = null
        val webSummaryDefinition = PluginConfig.webSummaryModelAlias.trim().takeIf(String::isNotEmpty)
            ?.let(::resolveOpenAi)
        if (webSummaryDefinition != null) {
            val webSummaryFirstChunk = PluginConfig.webSummaryFirstChunkTimeout.milliseconds
            webSummary = WebSummaryEndpoint(
                service = modelService(
                    webSummaryDefinition,
                    timeout = maxOf(timeout, webSummaryFirstChunk),
                    firstChunkTimeout = webSummaryFirstChunk,
                ),
                model = webSummaryDefinition.model,
                alias = webSummaryDefinition.alias,
                provider = webSummaryDefinition.provider,
            )
        } else if (PluginConfig.webSummaryModelApi.isNotBlank() &&
            PluginConfig.webSummaryModelToken.isNotBlank() &&
            PluginConfig.webSummaryModel.isNotBlank()
        ) {
            val webSummaryFirstChunk = PluginConfig.webSummaryFirstChunkTimeout.milliseconds
            webSummary = WebSummaryEndpoint(
                service = ModelService(
                    baseUrl = ModelCatalog.normalizeOpenAiApi(PluginConfig.webSummaryModelApi),
                    usageLogger = { message -> JChatGPT.logger.info(message) },
                    token = PluginConfig.webSummaryModelToken,
                    timeout = maxOf(timeout, webSummaryFirstChunk),
                    firstChunkTimeout = webSummaryFirstChunk,
                    extraBody = parseExtraBody(PluginConfig.webSummaryModelExtraBody),
                ),
                model = PluginConfig.webSummaryModel,
                alias = "legacy-web-summary",
                provider = providerName(PluginConfig.webSummaryModelApi),
            )
        }

        // 初始化推理模型
        reasoning = null
        val reasoningDefinition = PluginConfig.reasoningModelAlias.trim().takeIf(String::isNotEmpty)
            ?.let(::resolveOpenAi)
        if (reasoningDefinition != null) {
            val reasoningFirstChunk = PluginConfig.reasoningFirstChunkTimeout.milliseconds
            reasoning = AuxiliaryEndpoint(
                service = modelService(
                    reasoningDefinition,
                    timeout = maxOf(timeout, reasoningFirstChunk),
                    firstChunkTimeout = reasoningFirstChunk,
                ),
                model = reasoningDefinition.model,
                alias = reasoningDefinition.alias,
                provider = reasoningDefinition.provider,
            )
        } else if (PluginConfig.reasoningModelApi.isNotBlank() && PluginConfig.reasoningModelToken.isNotBlank()) {
            // 推理模型出首块前常有思考预热，比对话慢，使用单独放宽的首块超时；
            // socket 超时（两次读间隔，等首块时也归它管）不能小于首块预算，否则首块超时形同虚设
            val reasoningFirstChunk = PluginConfig.reasoningFirstChunkTimeout.milliseconds
            reasoning = AuxiliaryEndpoint(
                service = ModelService(
                    baseUrl = ModelCatalog.normalizeOpenAiApi(PluginConfig.reasoningModelApi),
                    usageLogger = { message -> JChatGPT.logger.info(message) },
                    token = PluginConfig.reasoningModelToken,
                    timeout = maxOf(timeout, reasoningFirstChunk),
                    firstChunkTimeout = reasoningFirstChunk,
                    extraBody = parseExtraBody(PluginConfig.reasoningModelExtraBody)
                ),
                model = PluginConfig.reasoningModel,
                alias = "legacy-reasoning",
                provider = providerName(PluginConfig.reasoningModelApi),
            )
        }

        // 初始化视觉模型
        visual = null
        val visualDefinition = PluginConfig.visualModelAlias.trim().takeIf(String::isNotEmpty)
            ?.let(::resolveOpenAi)
        if (visualDefinition != null) {
            val visualFirstChunk = PluginConfig.visualFirstChunkTimeout.milliseconds
            visual = AuxiliaryEndpoint(
                service = modelService(
                    visualDefinition,
                    timeout = maxOf(timeout, visualFirstChunk),
                    firstChunkTimeout = visualFirstChunk,
                ),
                model = visualDefinition.model,
                alias = visualDefinition.alias,
                provider = visualDefinition.provider,
            )
        } else if (PluginConfig.visualModelApi.isNotBlank() && PluginConfig.visualModelToken.isNotBlank()) {
            // 视觉模型需服务端先下载图片再出首块，比对话天然慢，使用单独放宽的首块超时；
            // socket 超时（两次读间隔，等首块时也归它管）不能小于首块预算，否则首块超时形同虚设
            val visualFirstChunk = PluginConfig.visualFirstChunkTimeout.milliseconds
            visual = AuxiliaryEndpoint(
                service = ModelService(
                    baseUrl = ModelCatalog.normalizeOpenAiApi(PluginConfig.visualModelApi),
                    usageLogger = { message -> JChatGPT.logger.info(message) },
                    token = PluginConfig.visualModelToken,
                    timeout = maxOf(timeout, visualFirstChunk),
                    firstChunkTimeout = visualFirstChunk,
                    extraBody = parseExtraBody(PluginConfig.visualModelExtraBody)
                ),
                model = PluginConfig.visualModel,
                alias = "legacy-visual",
                provider = providerName(PluginConfig.visualModelApi),
            )
        }

        // 载入提示词
        if (PluginConfig.promptFile.isNotEmpty()) {
            val file = JChatGPT.resolveConfigFile(PluginConfig.promptFile)
            systemPrompt = if (file.exists()) {
                file.readText()
            } else {
                // 迁移提示词
                file.writeText(PluginConfig.prompt)
                PluginConfig.prompt
            }

            // 空提示词兜底
            if (systemPrompt.isEmpty()) {
                systemPrompt = "你是一个乐于助人的助手"
            }
        }
    }

    private fun resolveOpenAi(alias: String): ResolvedModelDefinition? {
        val definition = ModelCatalog.resolve(alias)
        if (definition == null) {
            JChatGPT.logger.warning("Models.yml 中不存在模型别名：$alias")
            return null
        }
        if (definition.providerType !in setOf("openai", "openai-compatible", "openai_compatible")) {
            JChatGPT.logger.warning("模型别名 $alias 的提供商类型 ${definition.providerType} 不能用于 OpenAI 兼容客户端")
            return null
        }
        if (definition.api.isBlank() || definition.token.isBlank() || definition.model.isBlank()) {
            JChatGPT.logger.warning("模型别名 $alias 的 provider/api/token/model 配置不完整")
            return null
        }
        return definition
    }

    private fun modelService(
        definition: ResolvedModelDefinition,
        timeout: kotlin.time.Duration,
        firstChunkTimeout: kotlin.time.Duration,
        maxConcurrentRequests: Int? = null,
    ): ModelService = ModelService(
        baseUrl = ModelCatalog.normalizeOpenAiApi(definition.api),
        usageLogger = { message -> JChatGPT.logger.info(message) },
        token = definition.token,
        timeout = timeout,
        firstChunkTimeout = firstChunkTimeout,
        extraBody = parseExtraBody(definition.extraBody),
        maxConcurrentRequests = maxConcurrentRequests,
    )

    private fun providerName(api: String): String =
        runCatching { java.net.URI.create(api.trim()).host.orEmpty() }.getOrDefault("")
}
