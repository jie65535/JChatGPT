package top.jie65535.mirai.command

internal fun parseProfileGroupIds(raw: String): List<Long> = parseProfileIds(raw, "群号")

internal fun parseProfileUserIds(raw: String): List<Long> = parseProfileIds(raw, "用户号")

private fun parseProfileIds(raw: String, label: String): List<Long> {
    val ids = raw.split(',', '，', ';', '；')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { value -> value.toLongOrNull()?.takeIf { it > 0 } ?: error("无效$label: $value") }
        .distinct()
    require(ids.isNotEmpty()) { "至少需要一个$label" }
    return ids
}
