package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.mamoe.mirai.contact.MemberPermission
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageEvent
import top.jie65535.mirai.PluginConfig
import kotlin.time.Duration.Companion.seconds

class GroupManageAgent : BaseAgent(
    tool = Tool.function(
        name = "mute",
        description = "可用于禁言指定群成员，只有你是管理员且目标非管理或群主时有效，非必要不要轻易禁言别人，否则你可能会被禁用这个特权！",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("target") {
                    put("type", "integer")
                    put("description", "目标QQ号")
                }
                putJsonObject("durationM") {
                    put("type", "integer")
                    put("description", "禁言时长（分钟，目前暂时只支持1~10分钟，后续视情况增加上限）")
                }
            }
            putJsonArray("required") {
                add("target")
                add("durationM")
            }
        }
    )
) {
    override val isEnabled: Boolean
        get() = PluginConfig.canMute

    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        requireNotNull(args)
        val target = args.getValue("target").jsonPrimitive.long
        val duration = args.getValue("durationM").jsonPrimitive.int
        if (event !is GroupMessageEvent) {
            return "非群聊环境无法禁言"
        }
        if (event.group.botPermission == MemberPermission.MEMBER) {
            return "你并非管理，无法禁言他人"
        }
        val member = event.group[target]
        if (member == null) {
            return "未找到目标群成员"
        }

        if (member.isMuted) {
            return "该目标已被禁言，还剩 " + member.muteTimeRemaining.seconds.toString() + " 解除。"
        }

        // 禁言指定时长
        member.mute(duration.coerceIn(1, 10) * 60)
        return "已禁言目标"
    }
}