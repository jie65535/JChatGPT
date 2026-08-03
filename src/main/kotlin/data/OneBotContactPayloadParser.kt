package top.jie65535.mirai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object OneBotContactPayloadParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseObjectList(action: String, payload: String): List<JsonObject> {
        val root = runCatching { json.parseToJsonElement(payload) }
            .getOrElse { cause -> throw IllegalStateException("OneBot $action 返回了无效 JSON", cause) }
        val data = when (root) {
            is JsonArray -> root
            is JsonObject -> root.unwrapData(action)
            else -> throw IllegalStateException("OneBot $action 返回格式不是对象或数组")
        }
        return data.mapIndexed { index, element ->
            element as? JsonObject
                ?: throw IllegalStateException("OneBot $action 的 data[$index] 不是对象")
        }
    }

    private fun JsonObject.unwrapData(action: String): JsonArray {
        val status = text("status")
        val retcode = long("retcode")
        if ((status.isNotBlank() && status != "ok") || (retcode != null && retcode != 0L)) {
            val detail = text("message", "wording").ifBlank { "status=$status, retcode=${retcode ?: "unknown"}" }
            throw IllegalStateException("OneBot $action 调用失败: $detail")
        }
        return this["data"] as? JsonArray
            ?: throw IllegalStateException("OneBot $action 返回缺少数组 data")
    }
}

internal fun JsonObject.text(vararg keys: String): String =
    keys.asSequence()
        .mapNotNull { key -> this[key] as? JsonPrimitive }
        .mapNotNull(JsonPrimitive::contentOrNull)
        .firstOrNull(String::isNotBlank)
        .orEmpty()

internal fun JsonObject.int(vararg keys: String): Int? =
    keys.asSequence()
        .mapNotNull { key -> this[key] as? JsonPrimitive }
        .mapNotNull { value -> value.contentOrNull?.toIntOrNull() }
        .firstOrNull()

internal fun JsonObject.long(vararg keys: String): Long? =
    keys.asSequence()
        .mapNotNull { key -> this[key] as? JsonPrimitive }
        .mapNotNull { value -> value.contentOrNull?.toLongOrNull() }
        .firstOrNull()
