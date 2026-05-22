package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.mamoe.mirai.event.events.MessageEvent
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.PluginData
import top.jie65535.mirai.FavorabilityInfo
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class AdjustUserFavorabilityAgent : BaseAgent(
    tool = Tool.function(
        name = "adjustUserFavorability",
        description = """
            维护你对群友的认识（好感度、印象、标签、代号）。
            每次和某人有实质交流后建议调用一次，可与发言工具在同一轮发出，几乎不产生额外成本。

            触发场景：
            - 首次有像样的对话（建立初始印象和代号）
            - 对方透露身份/职业/偏好/技术栈（加 tag）
            - 互动产生明显情绪变化——开心/被逗/被冒犯（调 change）
            - 已有印象明显不准（更新 impression）

            change 默认为 0，只更新标签/印象时不用填 reason。
            tags 上限 5 个，满了须先 tags_remove 旧标签才能继续添加。
            好感度范围 -100 到 100，低于 0 时有概率忽略其消息，-100 则 100% 忽略。
        """.trimIndent(),
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("userId", buildJsonObject {
                    put("type", "integer")
                    put("description", "用户QQ号")
                })
                put("change", buildJsonObject {
                    put("type", "integer")
                    put("description", "好感度变化值（可正可负），默认为0")
                })
                put("reason", buildJsonObject {
                    put("type", "string")
                    put("description", "调整原因（change!=0时建议填写，供溯源）")
                })
                put("impression", buildJsonObject {
                    put("type", "string")
                    put("description", "对用户的印象描述，覆盖旧值（上限200字符）")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Bot给此人起的代号（非QQ昵称，上限20字符）")
                })
                put("tags_add", buildJsonObject {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "追加标签（自动去重，总数超5项返错，单项上限20字符）")
                })
                put("tags_remove", buildJsonObject {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "删除标签（不存在的项静默忽略）")
                })
            })
            putJsonArray("required") {
                add("userId")
            }
        }
    )
) {
    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        requireNotNull(args)

        val userId = args["userId"]?.jsonPrimitive?.longOrNull
            ?: return "错误：userId参数不能为空"

        val change = args["change"]?.jsonPrimitive?.intOrNull ?: 0
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull
        val impression = args["impression"]?.jsonPrimitive?.contentOrNull
        val name = args["name"]?.jsonPrimitive?.contentOrNull
        val tagsAdd = (args["tags_add"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
        val tagsRemove = (args["tags_remove"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }

        // 字段长度校验
        if (name != null && name.length > 20) return "错误：name不能超过20字符（当前${name.length}字符）"
        if (impression != null && impression.length > 200) return "错误：impression不能超过200字符（当前${impression.length}字符）"
        tagsAdd?.forEach { tag ->
            if (tag.length > 20) return "错误：tag「$tag」不能超过20字符"
        }

        val currentInfo = PluginData.userFavorability[userId] ?: FavorabilityInfo(userId)
        val currentValue = currentInfo.value

        val newValue = (currentValue + change).coerceIn(-100, 100)

        // 只在 change != 0 时记录原因
        val newReasons = if (change != 0 && reason != null) {
            val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            val newReason = "${timeFormatter.format(OffsetDateTime.now())}: $reason"
            if (currentInfo.reasons.size >= 10) {
                currentInfo.reasons.drop(1) + newReason
            } else {
                currentInfo.reasons + newReason
            }
        } else currentInfo.reasons

        // 处理标签
        val newTags = currentInfo.tags.toMutableList()
        tagsRemove?.forEach { tag -> newTags.remove(tag) }
        if (tagsAdd != null) {
            val toAdd = tagsAdd.filter { it !in newTags }
            if (newTags.size + toAdd.size > 5) {
                return "错误：标签已满（当前${newTags.size}项），须先用tags_remove删除旧标签。当前标签：[${newTags.joinToString(", ")}]"
            }
            newTags.addAll(toAdd)
        }

        val newInfo = FavorabilityInfo(
            userId = userId,
            value = newValue,
            reasons = newReasons,
            impression = impression ?: currentInfo.impression,
            name = name ?: currentInfo.name,
            tags = newTags
        )

        PluginData.userFavorability[userId] = newInfo
        JChatGPT.logger.info("用户 $userId 画像已更新：好感度($currentValue -> $newValue)，原因：$reason")

        return buildString {
            append("用户 $userId 画像已更新：好感度=$newValue")
            if (newTags.isNotEmpty()) append("，标签=[${newTags.joinToString(", ")}]")
            if (newInfo.name.isNotEmpty()) append("，代号=${newInfo.name}")
            if (newInfo.impression.isNotEmpty()) append("，印象=${newInfo.impression}")
        }
    }
}
