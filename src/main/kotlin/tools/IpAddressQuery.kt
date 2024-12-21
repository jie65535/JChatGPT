package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

class IpAddressQuery : BaseAgent(
    tool = Tool.function(
        name = "ipAddressQuery",
        description = "可查询IP地址归属地",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("ip") {
                    put("type", "string")
                    put("description", "IPv4地址")
                }
            }
            putJsonArray("required") {
                add("ip")
            }
        }
    )
) {
    override suspend fun execute(args: JsonObject?): String {
        requireNotNull(args)
        val ip = args.getValue("ip").jsonPrimitive.content
        val response = httpClient.get("https://api.52vmy.cn/api/query/itad?ip=$ip")
        return response.bodyAsText()
    }
}