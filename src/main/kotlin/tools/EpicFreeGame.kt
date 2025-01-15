package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.JsonObject

class EpicFreeGame : BaseAgent(
    tool = Tool.function(
        name = "getEpicFreeGames",
        description = "可查询Epic免费游戏",
        parameters = Parameters.Empty
    )
) {
    // https://docs.60s-api.viki.moe/

    override suspend fun execute(args: JsonObject?): String {
        // https://docs.60s-api.viki.moe/254044293e0
        val response = httpClient.get("http://60s-api.viki.moe/v2/epic")
        return response.bodyAsText()
    }
}