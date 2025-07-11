package top.jie65535.mirai

import com.aallam.openai.api.http.Timeout
import com.aallam.openai.client.Chat
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
import kotlin.time.Duration.Companion.milliseconds

object LargeLanguageModels {
    var chat: Chat? = null
    var reasoning: Chat? = null
    var visual: Chat? = null

    fun reload() {
        val timeout = PluginConfig.timeout.milliseconds
        if (PluginConfig.openAiApi.isNotBlank() && PluginConfig.openAiToken.isNotBlank()) {
            chat = OpenAI(
                token = PluginConfig.openAiToken,
                host = OpenAIHost(baseUrl = PluginConfig.openAiApi),
                timeout = Timeout(request = timeout, connect = timeout, socket = timeout)
            )
        }

        if (PluginConfig.reasoningModelApi.isNotBlank() && PluginConfig.reasoningModelToken.isNotBlank()) {
            reasoning = OpenAI(
                token = PluginConfig.reasoningModelToken,
                host = OpenAIHost(baseUrl = PluginConfig.reasoningModelApi),
                timeout = Timeout(request = timeout, connect = timeout, socket = timeout)
            )
        }

        if (PluginConfig.visualModelApi.isNotBlank() && PluginConfig.visualModelToken.isNotBlank()) {
            visual = OpenAI(
                token = PluginConfig.visualModelToken,
                host = OpenAIHost(baseUrl = PluginConfig.visualModelApi),
                timeout = Timeout(request = timeout, connect = timeout, socket = timeout)
            )
        }
    }
}