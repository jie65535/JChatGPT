package top.jie65535.mirai.profile

import java.util.UUID

object UserProfileReducer {
    fun reduce(
        current: UserProfileSnapshot,
        batch: ProfileHistoryBatch,
        response: ProfileModelResponse,
        model: String,
        promptVersion: String,
        summaryMaxLength: Int,
        advanceBackfillCursor: Boolean = true,
    ): ProfileReduction {
        val items = current.items.associateBy { it.id }.toMutableMap()
        val applied = mutableListOf<AppliedProfileOperation>()
        val skipped = mutableListOf<String>()

        response.operations.forEachIndexed { index, operation ->
            try {
                applyOperation(index, operation, current, batch, items)?.let(applied::add)
            } catch (cause: IllegalArgumentException) {
                skipped += cause.message ?: "operations[$index] 不符合画像协议"
            }
        }

        val hasSummaryRelevantChange = applied.any { it.action != ProfileOperationAction.CONFIRM }
        val summary = if (
            applied.isEmpty() || skipped.isNotEmpty() ||
            (!hasSummaryRelevantChange && current.summary.isNotBlank())
        ) {
            current.summary
        } else {
            try {
                ProfileContentRules.validateSummary(
                    ProfilePersistentText.normalizeSummary(response.summary, batch),
                    summaryMaxLength,
                ).ifEmpty { current.summary }
            } catch (cause: IllegalArgumentException) {
                skipped += "summary: ${cause.message}"
                current.summary
            }
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
        return ProfileReduction(profile, applied, skipped)
    }

    private fun applyOperation(
        index: Int,
        operation: ProfileModelOperation,
        current: UserProfileSnapshot,
        batch: ProfileHistoryBatch,
        items: MutableMap<String, UserProfileItem>,
    ): AppliedProfileOperation? {
        val evidence = operation.evidenceRefs.distinct().map { ref ->
            batch.evidenceByRef[ref]
                ?: throw IllegalArgumentException("operations[$index] 引用了不存在的证据 e:$ref")
        }
        require(evidence.isNotEmpty()) { "operations[$index] 缺少证据" }
        require(evidence.any { it.record.fromId == batch.userId }) {
            "operations[$index] 没有目标用户自己的发言证据"
        }

        val targetEvidence = evidence.filter { it.record.fromId == batch.userId }
        val evidenceTime = targetEvidence.maxOf { it.record.time }
        val firstEvidenceTime = targetEvidence.minOf { it.record.time }

        return when (operation.action) {
            ProfileOperationAction.ADD -> {
                require(operation.itemRef.isNullOrBlank()) {
                    "operations[$index] ADD 不能指定 item_ref"
                }
                val category = requireNotNull(operation.category) {
                    "operations[$index] ADD 缺少 category"
                }
                val confidence = requireNotNull(operation.confidence) {
                    "operations[$index] ADD 缺少 confidence"
                }
                val relatedUserId = resolveRelatedUser(index, category, operation.relatedUserAlias, batch)
                val content = validateContent(index, operation.content, batch, relatedUserId)
                val duplicate = items.values.any {
                    it.category == category &&
                            ProfileContentRules.normalizedKey(it.content) == ProfileContentRules.normalizedKey(content)
                }
                if (duplicate) return null

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
                item.toApplied(operation.action, operation.evidenceRefs)
            }

            ProfileOperationAction.UPDATE -> {
                val old = requireExistingItem(index, operation, current, items)
                val category = operation.category ?: old.category
                val confidence = operation.confidence ?: old.confidence
                val relatedUserId = resolveRelatedUser(
                    index,
                    category,
                    operation.relatedUserAlias,
                    batch,
                    fallback = old.relatedUserId,
                )
                val content = validateContent(index, operation.content, batch, relatedUserId)
                val updated = old.copy(
                    category = category,
                    content = content,
                    confidence = confidence,
                    relatedUserId = relatedUserId,
                    firstSeenAt = minOf(old.firstSeenAt, firstEvidenceTime),
                    lastConfirmedAt = maxOf(old.lastConfirmedAt, evidenceTime),
                )
                items[old.id] = updated
                updated.toApplied(operation.action, operation.evidenceRefs)
            }

            ProfileOperationAction.CONFIRM -> {
                val old = requireExistingItem(index, operation, current, items)
                val updated = old.copy(
                    confidence = operation.confidence ?: old.confidence,
                    firstSeenAt = minOf(old.firstSeenAt, firstEvidenceTime),
                    lastConfirmedAt = maxOf(old.lastConfirmedAt, evidenceTime),
                )
                items[old.id] = updated
                updated.toApplied(operation.action, operation.evidenceRefs)
            }

            ProfileOperationAction.DELETE -> {
                val old = requireExistingItem(index, operation, current, items)
                items.remove(old.id)
                old.toApplied(operation.action, operation.evidenceRefs)
            }
        }
    }

    private fun validateContent(
        index: Int,
        raw: String?,
        batch: ProfileHistoryBatch,
        relatedUserId: Long?,
    ): String {
        return ProfileContentRules.validate(
            ProfilePersistentText.normalizeItemContent(raw.orEmpty(), batch, relatedUserId),
            "operations[$index]",
        )
    }

    private fun requireExistingItem(
        index: Int,
        operation: ProfileModelOperation,
        referenceProfile: UserProfileSnapshot,
        items: Map<String, UserProfileItem>,
    ): UserProfileItem {
        val itemRef = operation.itemRef?.takeIf { it.isNotBlank() }
        val referencedItem = ProfileItemReferences.resolve(referenceProfile, itemRef)
            ?: throw IllegalArgumentException(
                "operations[$index] ${operation.action} 指向不存在的画像条目 ${itemRef ?: "（缺少 item_ref）"}"
            )
        return items[referencedItem.id]
            ?: throw IllegalArgumentException(
                "operations[$index] ${operation.action} 重复操作了画像条目 $itemRef"
            )
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

}
