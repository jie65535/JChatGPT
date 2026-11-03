package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import net.mamoe.mirai.event.events.MessageEvent
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.config.ModelConfig
import top.jie65535.mirai.config.PluginConfig
import top.jie65535.mirai.data.ModelUsageRecorder

internal abstract class DeepSeekToolAgent(
    private val operation: String,
    tool: Tool,
) : BaseAgent(tool) {
    // 避免 OkHttp 在应用层退避之前立即重试 HTTP 408。
    override val retryOnConnectionFailure: Boolean = false

    override val isEnabled: Boolean
        get() = configuredEndpoint() != null

    internal abstract fun validateArguments(args: JsonObject): JsonObject

    override suspend fun execute(args: JsonObject?): String = executeRequest(args, null)

    override suspend fun execute(args: JsonObject?, event: MessageEvent): String = executeRequest(args, event)

    private suspend fun executeRequest(args: JsonObject?, event: MessageEvent?): String {
        val endpoint = configuredEndpoint()
            ?: return DeepSeekToolsClient.error("configuration_error", "DeepSeek 工具未启用或 provider 配置无效")
        val request = try {
            validateArguments(requireNotNull(args) { "必须提供 JSON 参数" })
        } catch (e: IllegalArgumentException) {
            return DeepSeekToolsClient.error("invalid_request_error", e.message ?: "参数无效")
        }
        return try {
            val response = DeepSeekToolsClient(
                httpClient,
                logDiagnostic = { JChatGPT.logger.info(it) },
            ).request(endpoint, operation, request)
            if (response.billable && event != null) {
                ModelUsageRecorder.recordUnits(
                    event = event,
                    endpointLabel = "deepseek-$operation",
                    modelAlias = "",
                    provider = endpoint.provider,
                    model = operation,
                    usageKind = "web_$operation",
                    unit = "requests",
                    totalUnits = 1,
                )
            }
            response.body
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 不输出请求头、凭据、网页正文或可能包含这些内容的异常消息。
            JChatGPT.logger.warning("DeepSeek $operation 请求失败: ${e::class.simpleName}")
            DeepSeekToolsClient.error("request_error", "DeepSeek $operation 请求失败：${e::class.simpleName}")
        }
    }

    override fun limitOutput(content: String, maxLength: Int): String = limitJsonOutput(content, maxLength)

    private fun configuredEndpoint(): DeepSeekToolsClient.Endpoint? = DeepSeekToolsClient.resolveEndpoint(
        PluginConfig.deepSeekToolsProvider,
        ModelConfig.providers,
    )

    companion object {
        private const val TRUNCATION_MARKER = "\n[系统提示：内容过长，此字段已截断]"
        private val textFields = setOf("content", "summary", "snippet")

        internal fun requireString(args: JsonObject, name: String, maxLength: Int): String {
            val value = args[name] as? JsonPrimitive
            require(value != null && value.isString) { "$name 必须是字符串" }
            val text = value.content
            require(text.codePointCount(0, text.length) in 1..maxLength) {
                "$name 长度必须为 1 到 $maxLength 个 Unicode 字符"
            }
            return text
        }

        internal fun limitJsonOutput(content: String, maxLength: Int): String {
            val limit = maxLength.coerceAtLeast(2)
            if (content.length <= limit) return content
            val root = try {
                Json.parseToJsonElement(content) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            }
            if (root != null) {
                val compact = root.toString()
                if (compact.length <= limit) return compact
                var best = shortenText(root, 0).toString()
                if (best.length <= limit) {
                    var low = 0
                    var high = minOf(content.length, limit)
                    while (low < high) {
                        val middle = low + (high - low + 1) / 2
                        val candidate = shortenText(root, middle).toString()
                        if (candidate.length <= limit) {
                            low = middle
                            best = candidate
                        } else {
                            high = middle - 1
                        }
                    }
                    return best
                }
            }
            val error = DeepSeekToolsClient.error(
                "tool_output_limit",
                "响应超过工具输出上限，无法完整保留结果元数据。搜索请减少 count 或关闭 need_content；长网页请使用可用的定向提炼工具，或由管理员提高输出上限。",
            )
            return if (error.length <= limit) error else "{}"
        }

        private fun shortenText(element: JsonElement, maxChars: Int): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapValues { (key, value) ->
                if (key in textFields && value is JsonPrimitive && value.isString &&
                    value.content.length > maxChars + TRUNCATION_MARKER.length + 1) {
                    val text = value.content
                    val end = if (maxChars > 0 && text[maxChars - 1].isHighSurrogate()) maxChars - 1 else maxChars
                    JsonPrimitive(text.take(end) + TRUNCATION_MARKER)
                } else {
                    shortenText(value, maxChars)
                }
            })
            is JsonArray -> JsonArray(element.map { shortenText(it, maxChars) })
            else -> element
        }
    }
}
