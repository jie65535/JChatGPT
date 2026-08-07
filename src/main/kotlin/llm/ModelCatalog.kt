package top.jie65535.mirai.llm

import top.jie65535.mirai.config.ModelConfig
import top.jie65535.mirai.config.ModelDefinition
import top.jie65535.mirai.config.ModelProviderDefinition
import top.jie65535.mirai.config.PluginConfig

data class ResolvedModelDefinition(
    val alias: String,
    val provider: String,
    val providerType: String,
    val api: String,
    val token: String,
    val model: String,
    val extraBody: String,
)

/** Resolves shared model aliases and keeps legacy Config.yml fallback logic in one place. */
object ModelCatalog {
    private const val DEFAULT_DASHSCOPE_IMAGE_API =
        "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"

    private val openAiProviderTypes = setOf(
        "openai",
        "openai-compatible",
        "openai_compatible",
    )
    private val supportedProviderTypes = openAiProviderTypes + setOf(
        "dashscope",
    )

    fun resolve(alias: String): ResolvedModelDefinition? =
        resolve(alias, ModelConfig.providers, ModelConfig.models)

    internal fun resolve(
        alias: String,
        providers: List<ModelProviderDefinition>,
        models: List<ModelDefinition>,
    ): ResolvedModelDefinition? {
        val normalizedAlias = alias.trim()
        if (normalizedAlias.isEmpty()) return null
        val model = models.filter { it.name.trim() == normalizedAlias }.singleOrNull() ?: return null
        val provider = providers.filter { it.name.trim() == model.provider.trim() }.singleOrNull()
            ?: return null
        return model.resolve(provider, normalizedAlias)
    }

    fun validationIssues(): List<String> = validationIssues(ModelConfig.providers, ModelConfig.models)

    fun roleValidationIssues(): List<String> {
        val bindings = buildList {
            add(Triple("主聊天", PluginConfig.chatModelAlias, openAiProviderTypes))
            PluginConfig.chatFallbackModelAliases.forEachIndexed { index, alias ->
                add(Triple("聊天备用 ${index + 1}", alias, openAiProviderTypes))
            }
            add(
                Triple(
                    "画像",
                    PluginConfig.profileModelAlias.ifBlank { PluginConfig.chatModelAlias },
                    openAiProviderTypes,
                )
            )
            add(Triple("推理", PluginConfig.reasoningModelAlias, openAiProviderTypes))
            add(Triple("视觉", PluginConfig.visualModelAlias, openAiProviderTypes))
            add(Triple("网页摘要", PluginConfig.webSummaryModelAlias, openAiProviderTypes))
            add(Triple("图像", PluginConfig.imageModelAlias, setOf("dashscope")))
            add(Triple("TTS", PluginConfig.ttsModelAlias, setOf("dashscope")))
        }
        return bindings.mapNotNull { (role, alias, allowedTypes) ->
            bindingValidationIssue(
                role = role,
                alias = alias,
                allowedTypes = allowedTypes,
                providers = ModelConfig.providers,
                models = ModelConfig.models,
            )
        }.distinct()
    }

    internal fun bindingValidationIssue(
        role: String,
        alias: String,
        allowedTypes: Set<String>,
        providers: List<ModelProviderDefinition>,
        models: List<ModelDefinition>,
    ): String? {
        val normalizedAlias = alias.trim()
        if (normalizedAlias.isEmpty()) return null
        val definition = resolve(normalizedAlias, providers, models)
            ?: return "Models.yml 的 $role 角色引用了无效模型别名：$normalizedAlias"
        return if (definition.providerType !in allowedTypes) {
            "Models.yml 的 $role 角色不能使用 provider type ${definition.providerType}：$normalizedAlias"
        } else {
            null
        }
    }

    internal fun validationIssues(
        providers: List<ModelProviderDefinition>,
        models: List<ModelDefinition>,
    ): List<String> = buildList {
        val providerNames = providers.map { it.name.trim() }
        providerNames.filter(String::isEmpty).forEach { add("Models.yml 存在空 provider 名称") }
        providerNames.groupingBy(String::toString).eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach { add("Models.yml provider 名称重复：$it") }

        val modelNames = models.map { it.name.trim() }
        modelNames.filter(String::isEmpty).forEach { add("Models.yml 存在空模型别名") }
        modelNames.groupingBy(String::toString).eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach { add("Models.yml 模型别名重复：$it") }

        providers.forEach { provider ->
            val name = provider.name.trim().ifBlank { "<empty>" }
            val type = provider.type.trim().lowercase().ifBlank { "openai" }
            if (type !in supportedProviderTypes) add("Models.yml provider $name 的 type 不受支持：$type")
            if (type != "dashscope" && provider.api.isBlank()) add("Models.yml provider $name 未配置 api")
            if (provider.token.isBlank()) add("Models.yml provider $name 未配置 token")
        }
        models.forEach { model ->
            val alias = model.name.trim().ifBlank { "<empty>" }
            val providerName = model.provider.trim()
            if (providerName.isEmpty() || providerNames.count { it == providerName } != 1) {
                add("Models.yml 模型 $alias 引用的 provider 无效：${providerName.ifBlank { "<empty>" }}")
            }
            if (model.model.isBlank()) add("Models.yml 模型 $alias 未配置实际模型名")
        }
    }.distinct()

    fun resolveImage(): ResolvedModelDefinition? =
        resolveDashScope(PluginConfig.imageModelAlias)
            ?: legacy(
                alias = "legacy-image",
                provider = "dashscope",
                providerType = "dashscope",
                api = DEFAULT_DASHSCOPE_IMAGE_API,
                token = PluginConfig.dashScopeApiKey,
                model = PluginConfig.imageModel,
            )

    fun resolveTts(): ResolvedModelDefinition? =
        resolveDashScope(PluginConfig.ttsModelAlias)
            ?: legacy(
                alias = "legacy-tts",
                provider = "dashscope",
                providerType = "dashscope",
                api = DEFAULT_DASHSCOPE_IMAGE_API,
                token = PluginConfig.dashScopeApiKey,
                model = PluginConfig.ttsModel,
            )

    fun normalizeOpenAiApi(api: String): String = api.trim().trimEnd('/') + "/"

    private fun resolveDashScope(alias: String): ResolvedModelDefinition? =
        resolve(alias)
            ?.takeIf { it.providerType == "dashscope" && it.token.isNotBlank() && it.model.isNotBlank() }
            ?.let { it.copy(api = it.api.ifBlank { DEFAULT_DASHSCOPE_IMAGE_API }) }

    private fun ModelDefinition.resolve(
        provider: ModelProviderDefinition,
        alias: String,
    ): ResolvedModelDefinition = ResolvedModelDefinition(
        alias = alias,
        provider = provider.name.trim(),
        providerType = provider.type.trim().lowercase().ifBlank { "openai" },
        api = provider.api.trim(),
        token = provider.token.trim(),
        model = model.trim(),
        extraBody = extraBody,
    )

    private fun legacy(
        alias: String,
        provider: String,
        providerType: String,
        api: String,
        token: String,
        model: String,
        extraBody: String = "",
    ): ResolvedModelDefinition? {
        if (api.isBlank() || token.isBlank() || model.isBlank()) return null
        return ResolvedModelDefinition(alias, provider, providerType, api, token, model.trim(), extraBody)
    }
}
