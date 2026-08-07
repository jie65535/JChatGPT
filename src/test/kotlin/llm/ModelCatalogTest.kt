package top.jie65535.mirai.llm

import top.jie65535.mirai.config.ModelDefinition
import top.jie65535.mirai.config.ModelProviderDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCatalogTest {
    @Test
    fun resolvesModelAliasThroughProvider() {
        val resolved = ModelCatalog.resolve(
            alias = "chat-main",
            providers = listOf(
                ModelProviderDefinition(
                    name = "deepseek",
                    type = "openai",
                    api = "https://api.deepseek.com/v1/",
                    token = "secret",
                )
            ),
            models = listOf(
                ModelDefinition(
                    name = "chat-main",
                    provider = "deepseek",
                    model = "deepseek-chat",
                    extraBody = "{\"thinking\":false}",
                )
            ),
        )

        requireNotNull(resolved)
        assertEquals("chat-main", resolved.alias)
        assertEquals("deepseek", resolved.provider)
        assertEquals("openai", resolved.providerType)
        assertEquals("deepseek-chat", resolved.model)
        assertEquals("secret", resolved.token)
    }

    @Test
    fun rejectsAmbiguousAliasesAndProviders() {
        val provider = ModelProviderDefinition("deepseek", "openai", "https://api.deepseek.com/v1/", "secret")
        val model = ModelDefinition("chat-main", "deepseek", "deepseek-chat")

        assertNull(ModelCatalog.resolve("chat-main", listOf(provider), listOf(model, model)))
        assertNull(ModelCatalog.resolve("chat-main", listOf(provider, provider), listOf(model)))
        assertNull(
            ModelCatalog.resolve(
                "chat-main",
                listOf(provider),
                listOf(model.copy(provider = "missing")),
            )
        )
    }

    @Test
    fun reportsInvalidCatalogEntries() {
        val issues = ModelCatalog.validationIssues(
            providers = listOf(
                ModelProviderDefinition("duplicate", "openai", "https://example.com/v1/", "token"),
                ModelProviderDefinition("duplicate", "unknown", "", ""),
            ),
            models = listOf(
                ModelDefinition("same", "duplicate", "model-a"),
                ModelDefinition("same", "missing", ""),
            ),
        )

        assertTrue(issues.any { it.contains("provider 名称重复") })
        assertTrue(issues.any { it.contains("模型别名重复") })
        assertTrue(issues.any { it.contains("type 不受支持") })
        assertTrue(issues.any { it.contains("未配置 token") })
        assertTrue(issues.any { it.contains("引用的 provider 无效") })
        assertTrue(issues.any { it.contains("未配置实际模型名") })
    }

    @Test
    fun reportsRoleProtocolMismatch() {
        val providers = listOf(
            ModelProviderDefinition(
                name = "dashscope-native",
                type = "dashscope",
                token = "secret",
            )
        )
        val models = listOf(ModelDefinition("image-main", "dashscope-native", "qwen-image"))

        val issue = ModelCatalog.bindingValidationIssue(
            role = "主聊天",
            alias = "image-main",
            allowedTypes = setOf("openai"),
            providers = providers,
            models = models,
        )
        assertTrue(issue?.contains("不能使用 provider type dashscope") == true)
        assertNull(
            ModelCatalog.bindingValidationIssue(
                role = "图像",
                alias = "image-main",
                allowedTypes = setOf("dashscope"),
                providers = providers,
                models = models,
            )
        )
    }
}
