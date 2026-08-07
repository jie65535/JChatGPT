package top.jie65535.mirai.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelConfigMigrationTest {
    @Test
    fun migratesLegacySettingsAndDeduplicatesProvidersAndModels() {
        val legacy = LegacyModelSettings(
            chat = openAi("https://api.deepseek.com/v1/", "token-a", "deepseek-chat"),
            chatFallbacks = listOf(
                openAi("https://api.deepseek.com/v1", "token-b", "deepseek-chat"),
            ),
            profile = openAi("https://api.deepseek.com/v1/", "token-a", "deepseek-chat"),
            reasoning = openAi("https://api.deepseek.com/v1/", "token-a", "deepseek-reasoner"),
            visual = openAi("https://dashscope.aliyuncs.com/compatible-mode/v1/", "token-c", "qwen-vl-plus"),
            webSummary = openAi("", "", ""),
            dashScopeToken = "dashscope-token",
            imageModel = "qwen-image-2.0",
            ttsModel = "qwen3-tts-flash",
        )

        val result = ModelConfigMigration.migrate(emptyList(), emptyList(), ModelRoleBindings(), legacy)

        assertEquals(4, result.providers.size)
        assertEquals(6, result.models.size)
        assertEquals("chat-main", result.bindings.chat)
        assertEquals("chat-main", result.bindings.profile)
        assertEquals(listOf("chat-fallback-1"), result.bindings.chatFallbacks)
        assertEquals("reasoning-main", result.bindings.reasoning)
        assertEquals("visual-main", result.bindings.visual)
        assertEquals("", result.bindings.webSummary)
        assertEquals("image-main", result.bindings.image)
        assertEquals("tts-main", result.bindings.tts)
        assertEquals(1, result.providers.count { it.type == "dashscope" })
    }

    @Test
    fun preservesExistingEntriesAndBindingsAndIsIdempotent() {
        val existingProvider = ModelProviderDefinition("custom", "openai", "https://api.deepseek.com/v1", "token-a")
        val existingModel = ModelDefinition("my-chat", "custom", "deepseek-chat")
        val bindings = ModelRoleBindings(chat = "my-chat", visual = "manual-visual")
        val legacy = LegacyModelSettings(
            chat = openAi("https://api.deepseek.com/v1/", "token-a", "deepseek-chat"),
            chatFallbacks = emptyList(),
            profile = openAi("https://api.deepseek.com/v1/", "token-a", "deepseek-chat"),
            reasoning = openAi("", "", ""),
            visual = openAi("https://example.com/v1", "other", "vision"),
            webSummary = openAi("", "", ""),
            dashScopeToken = "",
            imageModel = "qwen-image-2.0",
            ttsModel = "qwen3-tts-flash",
        )

        val first = ModelConfigMigration.migrate(listOf(existingProvider), listOf(existingModel), bindings, legacy)
        assertEquals(listOf(existingProvider), first.providers)
        assertEquals(listOf(existingModel), first.models)
        assertEquals("my-chat", first.bindings.chat)
        assertEquals("my-chat", first.bindings.profile)
        assertEquals("manual-visual", first.bindings.visual)

        val second = ModelConfigMigration.migrate(first.providers, first.models, first.bindings, legacy)
        assertEquals(first.providers, second.providers)
        assertEquals(first.models, second.models)
        assertEquals(first.bindings, second.bindings)
        assertEquals(0, second.addedProviders)
        assertEquals(0, second.addedModels)
        assertTrue(!second.changed)
    }

    @Test
    fun leavesFreshInstallEmptyWhenNoCredentialsExist() {
        val empty = openAi("", "", "")
        val legacy = LegacyModelSettings(
            chat = empty,
            chatFallbacks = emptyList(),
            profile = empty,
            reasoning = empty,
            visual = empty,
            webSummary = empty,
            dashScopeToken = "",
            imageModel = "qwen-image-2.0",
            ttsModel = "qwen3-tts-flash",
        )

        val result = ModelConfigMigration.migrate(emptyList(), emptyList(), ModelRoleBindings(), legacy)

        assertTrue(result.providers.isEmpty())
        assertTrue(result.models.isEmpty())
        assertEquals(ModelRoleBindings(), result.bindings)
        assertTrue(!result.changed)
    }

    @Test
    fun allocatesNewNamesWithoutOverwritingConflicts() {
        val providers = listOf(ModelProviderDefinition("deepseek", "openai", "https://other.example/v1", "other"))
        val models = listOf(ModelDefinition("chat-main", "deepseek", "other-model"))
        val empty = openAi("", "", "")
        val legacy = LegacyModelSettings(
            chat = openAi("https://api.deepseek.com/v1", "token-a", "deepseek-chat"),
            chatFallbacks = emptyList(),
            profile = empty,
            reasoning = empty,
            visual = empty,
            webSummary = empty,
            dashScopeToken = "",
            imageModel = "",
            ttsModel = "",
        )

        val result = ModelConfigMigration.migrate(providers, models, ModelRoleBindings(), legacy)

        assertEquals("deepseek-2", result.providers.last().name)
        assertEquals("chat-main-2", result.models.last().name)
        assertEquals("chat-main-2", result.bindings.chat)
    }

    private fun openAi(api: String, token: String, model: String) = LegacyOpenAiModel(api, token, model)
}
