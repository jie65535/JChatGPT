package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

class WeatherService : BaseAgent(
    tool = Tool.function(
        name = "queryWeather",
        description = "可用于查询某城市地区天气.",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("city") {
                    put("type", "string")
                    put("description", "城市地区，如\"深圳市\"")
                }
                putJsonObject("time_range") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("day")
                        add("three")
                        add("many")
                    }
                    put("description", "时间范围，仅当天天气可获得最详细信息，三天和更多只能获得简单信息。")
                }
            }
            putJsonArray("required") {
                add("city")
            }
        }
    )
) {
    override val loadingMessage: String
        get() = "观天中..."

    override suspend fun execute(args: JsonObject?): String {
        requireNotNull(args)
        val city = args.getValue("city").jsonPrimitive.content
        val timeRange = args["time_range"]?.jsonPrimitive?.contentOrNull
        val response = httpClient.get(
            buildString {
                append(when (timeRange) {
                    "many" -> "https://api.52vmy.cn/api/query/tian/many"
                    "three" -> "https://api.52vmy.cn/api/query/tian/three"
                    else -> "https://api.52vmy.cn/api/query/tian"
                })
                append("?city=")
                append(city)
            }
        )
        return response.bodyAsText()
    }
}