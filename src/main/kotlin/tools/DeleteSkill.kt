package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.mamoe.mirai.event.events.MessageEvent
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.PluginConfig
import top.jie65535.mirai.SkillStore

/**
 * 删除一个过时或失效的技能。
 */
class DeleteSkill : BaseAgent(
    tool = Tool.function(
        name = "deleteSkill",
        description = "删除一个已过时或失效的技能。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "要删除的技能名")
                }
            }
            putJsonArray("required") {
                add("name")
            }
        }
    )
) {
    override val isEnabled: Boolean
        get() = PluginConfig.skillsEnabled

    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        requireNotNull(args)
        val name = args.getValue("name").jsonPrimitive.content
        JChatGPT.logger.info("Delete skill: \"$name\"")
        return if (SkillStore.delete(name)) {
            "OK，技能 \"$name\" 已删除。"
        } else {
            "技能 \"$name\" 不存在。"
        }
    }
}
