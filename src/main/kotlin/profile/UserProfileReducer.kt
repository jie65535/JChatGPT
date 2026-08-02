package top.jie65535.mirai.profile

import java.util.UUID

object UserProfileReducer {
    private val overclaimPattern = Regex("深厚|扎实|精通|专家|导师|领袖|天才|极强|全栈|核心成员|公认")

    fun reduce(
        current: UserProfileSnapshot,
        batch: ProfileHistoryBatch,
        response: ProfileModelResponse,
        model: String,
        promptVersion: String,
        summaryMaxLength: Int,
        advanceBackfillCursor: Boolean = true,
    ): ProfileReduction {
        require(response.summary.length <= summaryMaxLength) {
            "画像摘要超过 ${summaryMaxLength} 字符"
        }

        val items = current.items.associateBy { it.id }.toMutableMap()
        val applied = mutableListOf<AppliedProfileOperation>()

        response.operations.forEachIndexed { index, operation ->
            val evidence = operation.evidenceRefs.distinct().map { ref ->
                batch.evidenceByRef[ref]
                    ?: throw IllegalArgumentException("operations[$index] 引用了不存在的证据 e:$ref")
            }
            require(evidence.isNotEmpty()) { "operations[$index] 缺少证据" }
            require(evidence.any { it.record.fromId == batch.userId }) {
                "operations[$index] 没有目标用户自己的发言证据"
            }

            val evidenceTime = evidence
                .asSequence()
                .filter { it.record.fromId == batch.userId }
                .maxOf { it.record.time }
            val firstEvidenceTime = evidence
                .asSequence()
                .filter { it.record.fromId == batch.userId }
                .minOf { it.record.time }

            when (operation.action) {
                ProfileOperationAction.ADD -> {
                    require(operation.itemId.isNullOrBlank()) {
                        "operations[$index] ADD 不能指定 item_id"
                    }
                    val category = requireNotNull(operation.category) {
                        "operations[$index] ADD 缺少 category"
                    }
                    val content = validateContent(index, operation.content)
                    val confidence = requireNotNull(operation.confidence) {
                        "operations[$index] ADD 缺少 confidence"
                    }
                    val relatedUserId = resolveRelatedUser(index, category, operation.relatedUserAlias, batch)
                    val duplicate = items.values.any {
                        it.category == category && normalize(it.content) == normalize(content)
                    }
                    if (duplicate) return@forEachIndexed

                    val item = UserProfileItem(
                        id = UUID.randomUUID().toString(),
                        category = category,
                        content = content,
                        confidence = confidence,
                        relatedUserId = relatedUserId,
                        firstSeenAt = firstEvidenceTime,
                        lastConfirmedAt = evidenceTime,
                    )
                    items[item.id] = item
                    applied += item.toApplied(operation.action, operation.evidenceRefs)
                }

                ProfileOperationAction.UPDATE -> {
                    val old = requireExistingItem(index, operation, items)
                    val category = operation.category ?: old.category
                    val content = validateContent(index, operation.content)
                    val confidence = operation.confidence ?: old.confidence
                    val relatedUserId = resolveRelatedUser(
                        index,
                        category,
                        operation.relatedUserAlias,
                        batch,
                        fallback = old.relatedUserId,
                    )
                    val updated = old.copy(
                        category = category,
                        content = content,
                        confidence = confidence,
                        relatedUserId = relatedUserId,
                        lastConfirmedAt = evidenceTime,
                    )
                    items[old.id] = updated
                    applied += updated.toApplied(operation.action, operation.evidenceRefs)
                }

                ProfileOperationAction.CONFIRM -> {
                    val old = requireExistingItem(index, operation, items)
                    val updated = old.copy(
                        confidence = operation.confidence ?: old.confidence,
                        lastConfirmedAt = evidenceTime,
                    )
                    items[old.id] = updated
                    applied += updated.toApplied(operation.action, operation.evidenceRefs)
                }

                ProfileOperationAction.DELETE -> {
                    val old = requireExistingItem(index, operation, items)
                    items.remove(old.id)
                    applied += old.toApplied(operation.action, operation.evidenceRefs)
                }
            }
        }

        val summary = if (applied.isEmpty()) {
            current.summary
        } else {
            response.summary.trim().ifEmpty { current.summary }
        }
        val changed = applied.isNotEmpty() || summary != current.summary
        val profile = current.copy(
            summary = summary,
            version = current.version + if (changed) 1 else 0,
            cursorTime = if (advanceBackfillCursor) batch.endTime else current.cursorTime,
            reliable = items.isNotEmpty(),
            model = model,
            promptVersion = promptVersion,
            updatedAt = System.currentTimeMillis(),
            items = items.values.sortedWith(
                compareBy<UserProfileItem>({ it.category.ordinal }, { it.firstSeenAt }, { it.id })
            ),
        )
        return ProfileReduction(profile, applied)
    }

    private fun validateContent(index: Int, raw: String?): String {
        val content = raw?.trim().orEmpty()
        require(content.isNotEmpty()) { "operations[$index] 缺少 content" }
        require(content.length <= 120) { "operations[$index].content 超过 120 字符" }
        require(!overclaimPattern.containsMatchIn(content)) {
            "operations[$index].content 包含夸张身份或能力判断"
        }
        return content
    }

    private fun requireExistingItem(
        index: Int,
        operation: ProfileModelOperation,
        items: Map<String, UserProfileItem>,
    ): UserProfileItem {
        val itemId = operation.itemId?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("operations[$index] ${operation.action} 缺少 item_id")
        return items[itemId]
            ?: throw IllegalArgumentException("operations[$index] 指向不存在的画像条目 $itemId")
    }

    private fun resolveRelatedUser(
        index: Int,
        category: ProfileCategory,
        alias: String?,
        batch: ProfileHistoryBatch,
        fallback: Long? = null,
    ): Long? {
        if (category != ProfileCategory.RELATIONSHIP_NOTE) return null
        if (alias.isNullOrBlank()) {
            return fallback ?: throw IllegalArgumentException(
                "operations[$index] relationship_note 缺少 related_user_alias"
            )
        }
        val related = batch.aliasToUserId[alias]
            ?: throw IllegalArgumentException("operations[$index] 的关联用户别名不存在: $alias")
        require(related != batch.userId) { "operations[$index] 不能建立指向自己的关系条目" }
        return related
    }

    private fun UserProfileItem.toApplied(
        action: ProfileOperationAction,
        evidenceRefs: List<Int>,
    ) = AppliedProfileOperation(
        action = action,
        itemId = id,
        category = category,
        content = content,
        confidence = confidence,
        relatedUserId = relatedUserId,
        evidenceRefs = evidenceRefs.distinct(),
    )

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[\\s\\p{Punct}，。；、！？（）【】‘’“”]+"), "")
}
