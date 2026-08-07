package top.jie65535.mirai.config

import java.net.URI

internal data class ModelRoleBindings(
    val chat: String = "",
    val chatFallbacks: List<String> = emptyList(),
    val profile: String = "",
    val reasoning: String = "",
    val visual: String = "",
    val webSummary: String = "",
    val image: String = "",
    val tts: String = "",
)

internal data class LegacyOpenAiModel(
    val api: String,
    val token: String,
    val model: String,
    val extraBody: String = "",
)

internal data class LegacyModelSettings(
    val chat: LegacyOpenAiModel,
    val chatFallbacks: List<LegacyOpenAiModel>,
    val profile: LegacyOpenAiModel,
    val reasoning: LegacyOpenAiModel,
    val visual: LegacyOpenAiModel,
    val webSummary: LegacyOpenAiModel,
    val dashScopeToken: String,
    val imageModel: String,
    val ttsModel: String,
)

internal data class ModelConfigMigrationResult(
    val providers: List<ModelProviderDefinition>,
    val models: List<ModelDefinition>,
    val bindings: ModelRoleBindings,
    val addedProviders: Int,
    val addedModels: Int,
    val bindingsChanged: Boolean,
) {
    val changed: Boolean
        get() = addedProviders > 0 || addedModels > 0 || bindingsChanged
}

internal object ModelConfigMigration {
    fun migrateLoadedConfig(): ModelConfigMigrationResult {
        val currentBindings = ModelRoleBindings(
            chat = PluginConfig.chatModelAlias,
            chatFallbacks = PluginConfig.chatFallbackModelAliases,
            profile = PluginConfig.profileModelAlias,
            reasoning = PluginConfig.reasoningModelAlias,
            visual = PluginConfig.visualModelAlias,
            webSummary = PluginConfig.webSummaryModelAlias,
            image = PluginConfig.imageModelAlias,
            tts = PluginConfig.ttsModelAlias,
        )
        val chat = LegacyOpenAiModel(
            api = PluginConfig.openAiApi,
            token = PluginConfig.openAiToken,
            model = PluginConfig.chatModel,
            extraBody = PluginConfig.chatModelExtraBody,
        )
        val legacy = LegacyModelSettings(
            chat = chat,
            chatFallbacks = PluginConfig.chatFallbacks.map { fallback ->
                LegacyOpenAiModel(
                    api = fallback.api.ifBlank { chat.api },
                    token = fallback.token.ifBlank { chat.token },
                    model = fallback.model.ifBlank { chat.model },
                    extraBody = fallback.extraBody.ifBlank { chat.extraBody },
                )
            },
            profile = LegacyOpenAiModel(
                api = PluginConfig.profileModelApi.ifBlank { chat.api },
                token = PluginConfig.profileModelToken.ifBlank { chat.token },
                model = PluginConfig.profileModel.ifBlank { chat.model },
                extraBody = PluginConfig.profileModelExtraBody.ifBlank { chat.extraBody },
            ),
            reasoning = LegacyOpenAiModel(
                api = PluginConfig.reasoningModelApi,
                token = PluginConfig.reasoningModelToken,
                model = PluginConfig.reasoningModel,
                extraBody = PluginConfig.reasoningModelExtraBody,
            ),
            visual = LegacyOpenAiModel(
                api = PluginConfig.visualModelApi,
                token = PluginConfig.visualModelToken,
                model = PluginConfig.visualModel,
                extraBody = PluginConfig.visualModelExtraBody,
            ),
            webSummary = LegacyOpenAiModel(
                api = PluginConfig.webSummaryModelApi,
                token = PluginConfig.webSummaryModelToken,
                model = PluginConfig.webSummaryModel,
                extraBody = PluginConfig.webSummaryModelExtraBody,
            ),
            dashScopeToken = PluginConfig.dashScopeApiKey,
            imageModel = PluginConfig.imageModel,
            ttsModel = PluginConfig.ttsModel,
        )
        val result = migrate(ModelConfig.providers, ModelConfig.models, currentBindings, legacy)
        if (result.providers != ModelConfig.providers) ModelConfig.providers = result.providers
        if (result.models != ModelConfig.models) ModelConfig.models = result.models
        if (result.bindings.chat != PluginConfig.chatModelAlias) PluginConfig.chatModelAlias = result.bindings.chat
        if (result.bindings.chatFallbacks != PluginConfig.chatFallbackModelAliases) {
            PluginConfig.chatFallbackModelAliases = result.bindings.chatFallbacks
        }
        if (result.bindings.profile != PluginConfig.profileModelAlias) PluginConfig.profileModelAlias = result.bindings.profile
        if (result.bindings.reasoning != PluginConfig.reasoningModelAlias) {
            PluginConfig.reasoningModelAlias = result.bindings.reasoning
        }
        if (result.bindings.visual != PluginConfig.visualModelAlias) PluginConfig.visualModelAlias = result.bindings.visual
        if (result.bindings.webSummary != PluginConfig.webSummaryModelAlias) {
            PluginConfig.webSummaryModelAlias = result.bindings.webSummary
        }
        if (result.bindings.image != PluginConfig.imageModelAlias) PluginConfig.imageModelAlias = result.bindings.image
        if (result.bindings.tts != PluginConfig.ttsModelAlias) PluginConfig.ttsModelAlias = result.bindings.tts
        return result
    }

    fun migrate(
        existingProviders: List<ModelProviderDefinition>,
        existingModels: List<ModelDefinition>,
        bindings: ModelRoleBindings,
        legacy: LegacyModelSettings,
    ): ModelConfigMigrationResult {
        val providers = existingProviders.toMutableList()
        val models = existingModels.toMutableList()
        val initialProviderCount = providers.size
        val initialModelCount = models.size

        fun bindOpenAi(current: String, preferredAlias: String, legacyModel: LegacyOpenAiModel): String =
            current.ifBlank {
                addModel(providers, models, preferredAlias, "openai", legacyModel)
            }

        fun bindDashScope(current: String, preferredAlias: String, model: String): String =
            current.ifBlank {
                addModel(
                    providers = providers,
                    models = models,
                    preferredAlias = preferredAlias,
                    providerType = "dashscope",
                    legacyModel = LegacyOpenAiModel("", legacy.dashScopeToken, model),
                )
            }

        val chat = bindOpenAi(bindings.chat, "chat-main", legacy.chat)
        val chatFallbacks = if (bindings.chatFallbacks.isNotEmpty()) {
            bindings.chatFallbacks
        } else {
            legacy.chatFallbacks.mapIndexedNotNull { index, fallback ->
                bindOpenAi("", "chat-fallback-${index + 1}", fallback).takeIf(String::isNotBlank)
            }
        }
        val migratedBindings = ModelRoleBindings(
            chat = chat,
            chatFallbacks = chatFallbacks,
            profile = bindOpenAi(bindings.profile, "profile-main", legacy.profile),
            reasoning = bindOpenAi(bindings.reasoning, "reasoning-main", legacy.reasoning),
            visual = bindOpenAi(bindings.visual, "visual-main", legacy.visual),
            webSummary = bindOpenAi(bindings.webSummary, "web-summary-main", legacy.webSummary),
            image = bindDashScope(bindings.image, "image-main", legacy.imageModel),
            tts = bindDashScope(bindings.tts, "tts-main", legacy.ttsModel),
        )
        return ModelConfigMigrationResult(
            providers = providers,
            models = models,
            bindings = migratedBindings,
            addedProviders = providers.size - initialProviderCount,
            addedModels = models.size - initialModelCount,
            bindingsChanged = migratedBindings != bindings,
        )
    }

    private fun addModel(
        providers: MutableList<ModelProviderDefinition>,
        models: MutableList<ModelDefinition>,
        preferredAlias: String,
        providerType: String,
        legacyModel: LegacyOpenAiModel,
    ): String {
        val token = legacyModel.token.trim()
        val modelName = legacyModel.model.trim()
        val api = legacyModel.api.trim()
        val extraBody = legacyModel.extraBody.trim()
        if (token.isEmpty() || modelName.isEmpty() || providerType == "openai" && api.isEmpty()) return ""

        val providerName = findOrAddProvider(providers, providerType, api, token)
        val reusable = models.firstOrNull { candidate ->
            candidate.name.isNotBlank() &&
                    models.count { it.name.trim() == candidate.name.trim() } == 1 &&
                    candidate.provider.trim() == providerName &&
                    candidate.model.trim() == modelName &&
                    candidate.extraBody.trim() == extraBody
        }
        if (reusable != null) return reusable.name.trim()

        val alias = uniqueName(preferredAlias, models.mapTo(HashSet()) { it.name.trim() })
        models += ModelDefinition(
            name = alias,
            provider = providerName,
            model = modelName,
            extraBody = extraBody,
        )
        return alias
    }

    private fun findOrAddProvider(
        providers: MutableList<ModelProviderDefinition>,
        type: String,
        api: String,
        token: String,
    ): String {
        val reusable = providers.firstOrNull { candidate ->
            candidate.name.isNotBlank() &&
                    providers.count { it.name.trim() == candidate.name.trim() } == 1 &&
                    normalizedType(candidate.type) == type &&
                    normalizedApi(candidate.api) == normalizedApi(api) &&
                    candidate.token.trim() == token
        }
        if (reusable != null) return reusable.name.trim()

        val name = uniqueName(providerBaseName(type, api), providers.mapTo(HashSet()) { it.name.trim() })
        providers += ModelProviderDefinition(name = name, type = type, api = api, token = token)
        return name
    }

    private fun normalizedType(type: String): String = when (type.trim().lowercase()) {
        "openai-compatible", "openai_compatible" -> "openai"
        else -> type.trim().lowercase()
    }

    private fun normalizedApi(api: String): String = api.trim().trimEnd('/')

    private fun providerBaseName(type: String, api: String): String {
        if (type == "dashscope") return "dashscope-native"
        val host = runCatching { URI.create(api).host?.lowercase() }.getOrNull().orEmpty()
        if (host.contains("deepseek")) return "deepseek"
        if (host.contains("dashscope")) return "dashscope-openai"
        if (host.contains("openai")) return "openai"
        val segment = host.split('.').firstOrNull { it !in setOf("", "api", "www", "v1") }.orEmpty()
        return segment.replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "openai" }
    }

    private fun uniqueName(preferred: String, occupied: Set<String>): String {
        if (preferred !in occupied) return preferred
        var suffix = 2
        while ("$preferred-$suffix" in occupied) suffix++
        return "$preferred-$suffix"
    }
}
