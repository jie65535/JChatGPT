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
 * 按需加载某个技能的正文进上下文。技能索引（name+简介）常驻系统提示词，
 * 当话题命中某技能时调用本工具读取其完整内容。
 */
class LoadSkill : BaseAgent(
    tool = Tool.function(
        name = "loadSkill",
        description = "当话题命中某个技能时，加载该技能的完整内容到上下文。可用技能见系统提示词中的技能索引。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "技能名（技能索引中的 name）")
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

    override val loadingMessage: String = "翻阅资料中..."

    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        requireNotNull(args)
        val name = args.getValue("name").jsonPrimitive.content
        JChatGPT.logger.info("Load skill: \"$name\"")
        val content = SkillStore.load(name)
        return content ?: "技能 \"$name\" 不存在，可用技能见系统提示词中的技能索引。"
    }
}
