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
     * 一个聊天接入点：封装了请求服务、模型名与温度。
     * 主接入点为列表第 0 项，其余为备用接入点，用于容灾切换。
     */
    data class ChatEndpoint(
        val service: ModelService,
        val model: String,
        val temperature: Double?,
        /** 唯一标识，用于健康状态跟踪与日志 */
        val label: String,
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
    var reasoning: ModelService? = null

    /**
     * 视觉模型
     */
    var visual: ModelService? = null

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
        val timeout = PluginConfig.timeout.milliseconds
        val firstChunkTimeout = PluginConfig.firstChunkTimeout.milliseconds

        // 初始化聊天接入点（主 + 备用），并重置健康状态
        cooldownUntil.clear()
        val endpoints = mutableListOf<ChatEndpoint>()
        if (PluginConfig.openAiApi.isNotBlank() && PluginConfig.openAiToken.isNotBlank()) {
            endpoints.add(
                ChatEndpoint(
                    service = ModelService(
                        baseUrl = PluginConfig.openAiApi,
                        token = PluginConfig.openAiToken,
                        timeout = timeout,
                        firstChunkTimeout = firstChunkTimeout,
                        extraBody = parseExtraBody(PluginConfig.chatModelExtraBody)
                    ),
                    model = PluginConfig.chatModel,
                    temperature = PluginConfig.chatTemperature,
                    label = "primary",
                )
            )

            // 备用接入点：留空字段继承主接入点配置
            PluginConfig.chatFallbacks.forEachIndexed { i, fb ->
                val api = fb.api.ifBlank { PluginConfig.openAiApi }
                val token = fb.token.ifBlank { PluginConfig.openAiToken }
                val model = fb.model.ifBlank { PluginConfig.chatModel }
                val extraBody = fb.extraBody.ifBlank { PluginConfig.chatModelExtraBody }
                if (api.isNotBlank() && token.isNotBlank()) {
                    endpoints.add(
                        ChatEndpoint(
                            service = ModelService(
                                baseUrl = api,
                                token = token,
                                timeout = timeout,
                                firstChunkTimeout = firstChunkTimeout,
                                extraBody = parseExtraBody(extraBody)
                            ),
                            model = model,
                            temperature = PluginConfig.chatTemperature,
                            label = "fallback$i:$model",
                        )
                    )
                }
            }
        }
        chatEndpoints = endpoints

        // 初始化推理模型
        if (PluginConfig.reasoningModelApi.isNotBlank() && PluginConfig.reasoningModelToken.isNotBlank()) {
            // 推理模型出首块前常有思考预热，比对话慢，使用单独放宽的首块超时；
            // socket 超时（两次读间隔，等首块时也归它管）不能小于首块预算，否则首块超时形同虚设
            val reasoningFirstChunk = PluginConfig.reasoningFirstChunkTimeout.milliseconds
            reasoning = ModelService(
                baseUrl = PluginConfig.reasoningModelApi,
                token = PluginConfig.reasoningModelToken,
                timeout = maxOf(timeout, reasoningFirstChunk),
                firstChunkTimeout = reasoningFirstChunk,
                extraBody = parseExtraBody(PluginConfig.reasoningModelExtraBody)
            )
        }

        // 初始化视觉模型
        if (PluginConfig.visualModelApi.isNotBlank() && PluginConfig.visualModelToken.isNotBlank()) {
            // 视觉模型需服务端先下载图片再出首块，比对话天然慢，使用单独放宽的首块超时；
            // socket 超时（两次读间隔，等首块时也归它管）不能小于首块预算，否则首块超时形同虚设
            val visualFirstChunk = PluginConfig.visualFirstChunkTimeout.milliseconds
            visual = ModelService(
                baseUrl = PluginConfig.visualModelApi,
                token = PluginConfig.visualModelToken,
                timeout = maxOf(timeout, visualFirstChunk),
                firstChunkTimeout = visualFirstChunk,
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
