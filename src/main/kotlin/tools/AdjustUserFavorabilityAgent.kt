package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.mamoe.mirai.event.events.MessageEvent
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.PluginData
import top.jie65535.mirai.FavorabilityInfo
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class AdjustUserFavorabilityAgent : BaseAgent(
    tool = Tool.function(
        name = "adjustUserFavorability",
        description = "可根据网友行为调整对其好感度，范围从-100到100。" +
                "默认为0表示陌生人，100表示非常好的朋友，-100表示已拉黑。" +
                "当好感度低于0时，有一定概率忽略该用户的消息，-100则100%忽略其消息。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("userId", buildJsonObject {
                    put("type", "integer")
                    put("description", "用户QQ号")
                })
                put("change", buildJsonObject {
                    put("type", "integer")
                    put("description", "好感度变化值（可正可负）")
                })
                put("reason", buildJsonObject {
                    put("type", "string")
                    put("description", "调整原因（供日志记录和溯源）")
                })
                put("impression", buildJsonObject {
                    put("type", "string")
                    put("description", "对用户的印象或称呼（可选）")
                })
            })
            putJsonArray("required") {
                add("userId")
                add("change")
                add("reason")
            }
        }
    )
) {
    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        requireNotNull(args)

        val userId = args["userId"]?.jsonPrimitive?.longOrNull
        val change = args["change"]?.jsonPrimitive?.intOrNull
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull
        val impression = args["impression"]?.jsonPrimitive?.contentOrNull

        if (userId == null || change == null || reason == null) {
            return "错误：userId、change和reason参数不能为空"
        }

        // 获取当前好感度信息
        val currentInfo = PluginData.userFavorability[userId] ?: FavorabilityInfo(userId)
        val currentValue = currentInfo.value

        // 计算新的好感度值，限制在-100~100范围内
        val newValue = (currentValue + change).coerceIn(-100, 100)

        // 更新原因列表
        val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val newReason = "${timeFormatter.format(OffsetDateTime.now())}: $reason"
        val newReasons = if (currentInfo.reasons.size >= 10) {
            // 保留最近的10条原因记录
            (currentInfo.reasons.drop(1) + newReason)
        } else {
            (currentInfo.reasons + newReason)
        }

        // 更新印象/画像
        val newImpression = impression ?: currentInfo.impression

        // 创建新的好感度信息
        val newInfo = FavorabilityInfo(
            userId = userId,
            value = newValue,
            reasons = newReasons,
            impression = newImpression
        )

        // 更新好感度
        PluginData.userFavorability[userId] = newInfo

        // 记录日志
        JChatGPT.logger.info("用户 $userId 的好感度 ($currentValue -> $newValue)，原因：$reason")

        return "用户 $userId 的好感度已更新为 $newValue"
    }
}