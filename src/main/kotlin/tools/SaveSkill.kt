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
 * 新增或整篇覆盖一个技能（全局，跨群共享）。
 * 用于把群里学到/被纠正的知识沉淀下来；迭代时先用 loadSkill 读全文，改好后整篇写回。
 */
class SaveSkill : BaseAgent(
    tool = Tool.function(
        name = "saveSkill",
        description = "沉淀或更新一个技能（知识文档），全局跨群共享。新增直接写；迭代时先 loadSkill 读全文，修改后整篇写回。技能名相同则覆盖。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "技能名，kebab-case，只能含字母/数字/下划线/连字符，如 kubejs-basics。相同则覆盖")
                }
                putJsonObject("description") {
                    put("type", "string")
                    put("description", "一句话简介，会常驻技能索引，决定你以后何时加载它")
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "技能正文（markdown），沉淀的知识、经验或提示词。整篇内容，会覆盖旧版本")
                }
            }
            putJsonArray("required") {
                add("name")
                add("description")
                add("content")
            }
        }
    )
) {
    override val isEnabled: Boolean
        get() = PluginConfig.skillsEnabled

    override val loadingMessage: String = "记下来了..."

    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        requireNotNull(args)
        val name = args.getValue("name").jsonPrimitive.content
        val description = args.getValue("description").jsonPrimitive.content
        val content = args.getValue("content").jsonPrimitive.content
        JChatGPT.logger.info("Save skill: \"$name\" - \"$description\"")
        val error = SkillStore.save(name, description, content)
        return error ?: "OK，技能 \"$name\" 已保存。"
    }
}
