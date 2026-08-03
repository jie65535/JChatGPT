package top.jie65535.mirai.profile

import top.jie65535.mirai.JChatGPT

internal object ProfileOperationLogger {
    fun log(context: String, reductions: Collection<ProfileReduction>) {
        format(context, reductions)?.let(JChatGPT.logger::info)
    }

    internal fun format(context: String, reductions: Collection<ProfileReduction>): String? {
        val operations = reductions.flatMap { reduction ->
            reduction.operations.map { operation -> reduction.profile.userId to operation }
        }
        if (operations.isEmpty()) return null

        return buildString {
            append("PROFILE_OPERATIONS ").append(context)
                .append(" operations=").appendLine(operations.size)
            operations.forEach { (userId, operation) ->
                append("- user=").append(userId)
                    .append(" action=").append(operation.action)
                    .append(" category=").append(operation.category.name.lowercase())
                    .append(" confidence=").append(operation.confidence.name.lowercase())
                operation.relatedUserId?.let { append(" related=").append(it) }
                append(" content=").appendLine(operation.content.normalized())
            }
        }.trimEnd()
    }

    private fun String.normalized(): String = trim().replace(Regex("\\s+"), " ")
}
