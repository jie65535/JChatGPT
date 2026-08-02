package top.jie65535.mirai.profile

object UserProfileCompactor {
    fun reduce(
        current: UserProfileSnapshot,
        supportStats: Map<String, ProfileItemSupportStats>,
        response: ProfileCompactionResponse,
        model: String,
        promptVersion: String,
        summaryMaxLength: Int,
    ): ProfileCompactionPlan {
        val items = current.items.associateBy { it.id }.toMutableMap()
        val usedItemIds = mutableSetOf<String>()
        val supportReassignments = mutableMapOf<String, String>()
        val applied = mutableListOf<AppliedProfileOperation>()
        val skippedOperations = mutableListOf<String>()
        var mergedGroups = 0
        var rewrittenItems = 0
        var deletedItems = 0

        response.merges.forEachIndexed { index, merge ->
            try {
                require(merge.itemRefs.size >= 2) { "至少需要两个 item_refs" }
                require(merge.itemRefs.distinct().size == merge.itemRefs.size) { "包含重复 item_ref" }
                val sourceItems = merge.itemRefs.map { ref -> resolve(current, ref, "merges[$index]") }
                require(sourceItems.none { it.id in usedItemIds }) { "重复操作了画像条目" }
                val category = sourceItems.first().category
                val relatedUserId = sourceItems.first().relatedUserId
                require(sourceItems.all { it.category == category }) { "只能合并同类别条目" }
                require(sourceItems.all { it.relatedUserId == relatedUserId }) {
                    "只能合并指向同一用户的关系条目"
                }
                val target = sourceItems.minWith(compareBy<UserProfileItem>({ it.firstSeenAt }, { it.id }))
                val content = normalizeAndValidate(
                    merge.content,
                    relationship = category == ProfileCategory.RELATIONSHIP_NOTE,
                    label = "merges[$index]",
                )
                val updated = target.copy(
                    content = content,
                    confidence = sourceItems.minBy { it.confidence.ordinal }.confidence,
                    firstSeenAt = sourceItems.minOf { it.firstSeenAt },
                    lastConfirmedAt = sourceItems.maxOf { it.lastConfirmedAt },
                )
                usedItemIds += sourceItems.map(UserProfileItem::id)
                sourceItems.forEach { source -> items.remove(source.id) }
                items[updated.id] = updated
                applied += updated.toApplied(ProfileOperationAction.UPDATE)
                sourceItems.filter { it.id != updated.id }.forEach { source ->
                    supportReassignments[source.id] = updated.id
                    applied += source.toApplied(ProfileOperationAction.DELETE)
                }
                mergedGroups++
            } catch (cause: IllegalArgumentException) {
                skippedOperations += "merges[$index]: ${cause.message}"
            }
        }
        response.rewrites.forEachIndexed { index, rewrite ->
            try {
                val old = resolve(current, rewrite.itemRef, "rewrites[$index]")
                require(old.id !in usedItemIds) { "重复操作了画像条目" }
                val content = normalizeAndValidate(
                    rewrite.content,
                    relationship = old.category == ProfileCategory.RELATIONSHIP_NOTE,
                    label = "rewrites[$index]",
                )
                if (ProfileContentRules.normalizedKey(content) == ProfileContentRules.normalizedKey(old.content)) {
                    return@forEachIndexed
                }
                usedItemIds += old.id
                val updated = old.copy(
                    content = content,
                )
                items[old.id] = updated
                applied += updated.toApplied(ProfileOperationAction.UPDATE)
                rewrittenItems++
            } catch (cause: IllegalArgumentException) {
                skippedOperations += "rewrites[$index]: ${cause.message}"
            }
        }
        response.deletes.forEachIndexed { index, delete ->
            try {
                val old = resolve(current, delete.itemRef, "deletes[$index]")
                require(old.id !in usedItemIds) { "重复操作了画像条目" }
                require(old.confidence != ProfileConfidence.HIGH) { "不能自动删除 high 置信条目" }
                val supports = supportStats[old.id]?.count ?: 0
                require(supports <= MAX_DELETE_SUPPORTS) {
                    "画像条目已有 $supports 次支持，不能自动删除"
                }
                usedItemIds += old.id
                items.remove(old.id)
                applied += old.toApplied(ProfileOperationAction.DELETE)
                deletedItems++
            } catch (cause: IllegalArgumentException) {
                skippedOperations += "deletes[$index]: ${cause.message}"
            }
        }
        val summary = if (skippedOperations.isNotEmpty()) {
            current.summary
        } else {
            runCatching {
                ProfileContentRules.validateSummary(
                    ProfilePersistentText.summaryForDisplay(response.summary),
                    summaryMaxLength,
                )
            }.getOrElse { cause ->
                skippedOperations += "summary: ${cause.message}"
                current.summary
            }.ifBlank { current.summary }
        }
        val summaryChanged = summary != current.summary
        val profile = if (applied.isEmpty() && !summaryChanged) current else current.copy(
                summary = summary,
                version = current.version + 1,
                reliable = items.isNotEmpty(),
                model = model,
                promptVersion = promptVersion,
                updatedAt = System.currentTimeMillis(),
                items = items.values.sortedWith(
                    compareBy<UserProfileItem>({ it.category.ordinal }, { it.firstSeenAt }, { it.id })
                ),
            )
        return ProfileCompactionPlan(
            reduction = ProfileReduction(profile, applied),
            supportReassignments = supportReassignments,
            mergedGroups = mergedGroups,
            rewrittenItems = rewrittenItems,
            deletedItems = deletedItems,
            skippedOperations = skippedOperations,
        )
    }

    private fun resolve(profile: UserProfileSnapshot, reference: String, label: String): UserProfileItem =
        ProfileItemReferences.resolve(profile, reference)
            ?: throw IllegalArgumentException("$label 指向不存在的画像条目 $reference")

    private fun normalizeAndValidate(raw: String, relationship: Boolean, label: String): String =
        ProfileContentRules.validate(
            ProfilePersistentText.itemForDisplay(raw, relationship),
            label,
        )

    private fun UserProfileItem.toApplied(action: ProfileOperationAction) = AppliedProfileOperation(
        action = action,
        itemId = id,
        category = category,
        content = content,
        confidence = confidence,
        relatedUserId = relatedUserId,
        evidenceRefs = emptyList(),
    )

    private const val MAX_DELETE_SUPPORTS = 1
}
