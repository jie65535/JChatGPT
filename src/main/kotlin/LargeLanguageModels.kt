package top.jie65535.mirai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration.Companion.milliseconds

object LargeLanguageModels {

    /**
     * 系统提示词
     */
    var systemPrompt: String = "你是一个乐于助人的助手"
        private set

    /**
     * 聊天助手
     */
    var chat: ModelService? = null

    /**
     * 推理模型
     */
    var reasoning: ModelService? = null

    /**
     * 视觉模型
     */
    var visual: ModelService? = null

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
        val timeout = PluginConfig.timeout.milliseconds
        val firstChunkTimeout = PluginConfig.firstChunkTimeout.milliseconds

        // 初始化聊天模型
        if (PluginConfig.openAiApi.isNotBlank() && PluginConfig.openAiToken.isNotBlank()) {
            chat = ModelService(
                baseUrl = PluginConfig.openAiApi,
                token = PluginConfig.openAiToken,
                timeout = timeout,
                firstChunkTimeout = firstChunkTimeout,
                extraBody = parseExtraBody(PluginConfig.chatModelExtraBody)
            )
        }

        // 初始化推理模型
        if (PluginConfig.reasoningModelApi.isNotBlank() && PluginConfig.reasoningModelToken.isNotBlank()) {
            reasoning = ModelService(
                baseUrl = PluginConfig.reasoningModelApi,
                token = PluginConfig.reasoningModelToken,
                timeout = timeout,
                firstChunkTimeout = firstChunkTimeout,
                extraBody = parseExtraBody(PluginConfig.reasoningModelExtraBody)
            )
        }

        // 初始化视觉模型
        if (PluginConfig.visualModelApi.isNotBlank() && PluginConfig.visualModelToken.isNotBlank()) {
            visual = ModelService(
                baseUrl = PluginConfig.visualModelApi,
                token = PluginConfig.visualModelToken,
                timeout = timeout,
                firstChunkTimeout = firstChunkTimeout,
                extraBody = parseExtraBody(PluginConfig.visualModelExtraBody)
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
}
