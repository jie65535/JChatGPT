package top.jie65535.mirai

import com.aallam.openai.api.http.Timeout
import com.aallam.openai.client.Chat
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
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
    var chat: Chat? = null

    /**
     * 推理模型
     */
    var reasoning: Chat? = null

    /**
     * 视觉模型
     */
    var visual: Chat? = null

    fun reload() {
        // 载入超时时间
        val timeout = PluginConfig.timeout.milliseconds

        // 初始化聊天模型
        if (PluginConfig.openAiApi.isNotBlank() && PluginConfig.openAiToken.isNotBlank()) {
            chat = OpenAI(
                token = PluginConfig.openAiToken,
                host = OpenAIHost(baseUrl = PluginConfig.openAiApi),
                timeout = Timeout(request = timeout, connect = timeout, socket = timeout)
            )
        }

        // 初始化推理模型
        if (PluginConfig.reasoningModelApi.isNotBlank() && PluginConfig.reasoningModelToken.isNotBlank()) {
            reasoning = OpenAI(
                token = PluginConfig.reasoningModelToken,
                host = OpenAIHost(baseUrl = PluginConfig.reasoningModelApi),
                timeout = Timeout(request = timeout, connect = timeout, socket = timeout)
            )
        }

        // 初始化视觉模型
        if (PluginConfig.visualModelApi.isNotBlank() && PluginConfig.visualModelToken.isNotBlank()) {
            visual = OpenAI(
                token = PluginConfig.visualModelToken,
                host = OpenAIHost(baseUrl = PluginConfig.visualModelApi),
                timeout = Timeout(request = timeout, connect = timeout, socket = timeout)
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