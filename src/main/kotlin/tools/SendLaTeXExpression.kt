package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.*
import net.mamoe.mirai.event.events.MessageEvent
import top.jie65535.mirai.LaTeXConverter
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource

class SendLaTeXExpression : BaseAgent(
    tool = Tool.function(
        name = "sendLaTeXExpression",
        description = "发送LaTeX数学表达式，将其渲染为图片并发送",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("expression") {
                    put("type", "string")
                    put("description", "LaTeX数学表达式")
                }
            }
            putJsonArray("required") {
                add("expression")
            }
        }
    )
) {
    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        requireNotNull(args)
        val expression = args.getValue("expression").jsonPrimitive.content
        
        try {
            // 将LaTeX表达式转换为图片
            val imageByteArray = LaTeXConverter.convertToImage(expression, "png")
            val resource = imageByteArray.toExternalResource("png")
            val image = event.subject.uploadImage(resource)
            
            // 发送图片消息
            event.subject.sendMessage(image)
            
            return "已成功将LaTeX表达式转为图片发送"
        } catch (ex: Throwable) {
            return "处理LaTeX表达式时发生异常: ${ex.message}"
        }
    }
}