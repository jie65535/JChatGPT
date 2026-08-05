package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class StopLoopAgent : BaseAgent(
    tool = Tool.function(
        name = "endConversation",
        description = """
            声明当前模型运行已经完成。主循环会先结算本轮其他工具和运行期间的新触发，
            再决定结束、继续处理或短暂等待指定用户的下一条消息。每轮完成时必须且只能调用一次。
            通常不传waitForFollowUp并直接结束；只有刚刚明确要求他人提供会影响后续处理的反馈时才等待，
            不要仅为了看看是否有人回应、保持活跃或参与普通闲聊而等待。
        """.trimIndent(),
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("waitForFollowUp") {
                    put("type", "object")
                    put(
                        "description",
                        "可选。结束当前运行后，非阻塞地等待指定用户在当前会话中的下一条消息；省略表示立即离开。"
                    )
                    putJsonObject("properties") {
                        putJsonObject("timeoutSeconds") {
                            put("type", "integer")
                            put("minimum", 5)
                            put("maximum", 120)
                            put("description", "可选，默认30秒。选择满足当前具体等待所需的最短时间。")
                        }
                        putJsonObject("fromUserIds") {
                            put("type", "array")
                            put("minItems", 1)
                            put("maxItems", 10)
                            put("uniqueItems", true)
                            put("description", "明确等待回复的QQ用户列表。不要猜测或编造用户ID。")
                            putJsonObject("items") {
                                put("type", "integer")
                            }
                        }
                        putJsonObject("condition") {
                            put("type", "string")
                            put("minLength", 1)
                            put("maxLength", 200)
                            put(
                                "description",
                                "用一句话描述可由后续消息验证的具体等待条件，不能只写看看有没有人回应。"
                            )
                        }
                    }
                    putJsonArray("required") {
                        add("fromUserIds")
                        add("condition")
                    }
                    put("additionalProperties", false)
                }
            }
            put("additionalProperties", false)
        }
    )
)
