package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.mamoe.mirai.event.EventPriority
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.FriendMessageEvent
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.event.nextEvent
import net.mamoe.mirai.message.data.content
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.PluginConfig
import kotlin.collections.getValue

class RequestOwner : BaseAgent(
    tool = Tool.function(
        name = "requestOwnerHelp",
        description = "当遇到无法处理的问题时，通过私聊请求系统管理员并阻塞等待其回复，默认超时时间5分钟，请求前建议提前告知群友。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "请求内容与上下文")
                }
            }
            putJsonArray("required") {
                add("content")
            }
        }
    )
) {
    override val isEnabled: Boolean
        get() = PluginConfig.ownerId > 0 && PluginConfig.requestOwnerWaitTimeout > 0

    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        requireNotNull(args)
        val owner = event.bot.getFriendOrFail(PluginConfig.ownerId)
        val content = args.getValue("content").jsonPrimitive.content
        JChatGPT.logger.info("请求主人协助中：$content")
        owner.sendMessage(JChatGPT.toMessage(owner, content))

        val nextEvent: FriendMessageEvent = withTimeout(PluginConfig.requestOwnerWaitTimeout) {
            GlobalEventChannel.nextEvent(EventPriority.MONITOR) { it.sender.id == PluginConfig.ownerId }
        }
        val response = nextEvent.message.content
        JChatGPT.logger.info("主人回复：$response")
        return response
    }
}