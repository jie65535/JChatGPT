package top.jie65535.mirai.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserProfileCompactorTest {
    @Test
    fun mergesRewritesAndDeletesWithinConservativeBounds() {
        val profile = profile()
        val plan = UserProfileCompactor.reduce(
            current = profile,
            supportStats = mapOf("style-item" to supportStats(1)),
            response = ProfileCompactionResponse(
                merges = listOf(
                    ProfileCompactionMerge(
                        itemRefs = listOf("P1", "P2"),
                        content = "关注并实际体验国内外大语言模型",
                    )
                ),
                rewrites = listOf(
                    ProfileCompactionRewrite(
                        itemRef = "P4",
                        content = "从事使用 C# 的上位机开发",
                    )
                ),
                deletes = listOf(
                    ProfileCompactionDelete(
                        itemRef = "P3",
                        reason = ProfileCompactionDeleteReason.OVER_SPECIFIC,
                    )
                ),
                summary = "该用户从事上位机开发，并关注大语言模型。",
            ),
            model = "test-model",
            promptVersion = "compact-v1",
            summaryMaxLength = 500,
        )

        assertEquals(2, plan.reduction.profile.items.size)
        assertEquals(profile.cursorTime, plan.reduction.profile.cursorTime)
        assertEquals(profile.snapshotEndTime, plan.reduction.profile.snapshotEndTime)
        assertEquals(profile.version + 1, plan.reduction.profile.version)
        assertEquals(1, plan.mergedGroups)
        assertEquals(1, plan.rewrittenItems)
        assertEquals(1, plan.deletedItems)
        assertEquals(mapOf("model-b" to "model-a"), plan.supportReassignments)
        assertEquals(4, plan.reduction.operations.size)
        val merged = plan.reduction.profile.items.single { it.category == ProfileCategory.INTEREST }
        assertEquals("model-a", merged.id)
        assertEquals(ProfileConfidence.LOW, merged.confidence)
        assertEquals("关注并实际体验国内外大语言模型", merged.content)
        assertFalse(plan.reduction.profile.items.any { it.id == "style-item" })
    }

    @Test
    fun skipsMergingDifferentCategories() {
        val profile = profile()
        val plan = UserProfileCompactor.reduce(
            current = profile,
            supportStats = emptyMap(),
            response = ProfileCompactionResponse(
                merges = listOf(
                    ProfileCompactionMerge(
                        itemRefs = listOf("P2", "P3"),
                        content = "关注模型并使用口语化表达",
                    )
                )
            ),
            model = "test-model",
            promptVersion = "compact-v1",
            summaryMaxLength = 500,
        )

        assertTrue(plan.reduction.operations.isEmpty())
        assertEquals(profile.version, plan.reduction.profile.version)
        assertTrue(plan.skippedOperations.single().contains("同类别"))
    }

    @Test
    fun skipsDeletingSupportedOrHighItemsButAllowsLowSupportMedium() {
        val supported = delete(profile(), "P3", mapOf("style-item" to supportStats(2)))
        assertTrue(supported.reduction.operations.isEmpty())
        assertTrue(supported.skippedOperations.single().contains("2 次支持"))

        val high = delete(profile(), "P4", mapOf("fact-item" to supportStats(1)))
        assertTrue(high.reduction.operations.isEmpty())
        assertTrue(high.skippedOperations.single().contains("high"))

        val medium = delete(profile(), "P2", mapOf("model-b" to supportStats(1)))
        assertEquals(1, medium.deletedItems)
        assertFalse(medium.reduction.profile.items.any { it.id == "model-b" })
    }

    @Test
    fun allowsDeletingClearlyNonProfileContentRegardlessOfConfidenceOrSupportCount() {
        val profile = profile()
        val allowed = UserProfileCompactor.reduce(
            current = profile,
            supportStats = mapOf("fact-item" to supportStats(1)),
            response = ProfileCompactionResponse(
                deletes = listOf(
                    ProfileCompactionDelete("P4", ProfileCompactionDeleteReason.NOT_PROFILE)
                ),
                summary = profile.summary,
            ),
            model = "test-model",
            promptVersion = "compact-v5",
            summaryMaxLength = 500,
        )

        assertEquals(1, allowed.deletedItems)
        assertFalse(allowed.reduction.profile.items.any { it.id == "fact-item" })

        val supported = UserProfileCompactor.reduce(
            current = profile,
            supportStats = mapOf("fact-item" to supportStats(2)),
            response = ProfileCompactionResponse(
                deletes = listOf(
                    ProfileCompactionDelete("P4", ProfileCompactionDeleteReason.NOT_PROFILE)
                ),
                summary = profile.summary,
            ),
            model = "test-model",
            promptVersion = "compact-v5",
            summaryMaxLength = 500,
        )

        assertEquals(1, supported.deletedItems)
        assertFalse(supported.reduction.profile.items.any { it.id == "fact-item" })
    }

    @Test
    fun compactionPromptUsesTemporaryReferencesAndSupportCounts() {
        val profile = profile()
        val prompt = ProfilePromptStore.buildCompactionUserPrompt(
            profile,
            mapOf("model-a" to supportStats(3)),
        )

        assertTrue(prompt.contains("[P1]"))
        assertTrue(prompt.contains("supports=3"))
        assertTrue(prompt.contains("item_range="))
        profile.items.forEach { assertFalse(prompt.contains(it.id)) }
    }

    @Test
    fun profilePromptsRequireAbsoluteDatesForTimeSensitiveFacts() {
        assertTrue(ProfilePromptStore.systemPrompt.contains("截至 YYYY-MM-DD"))
        assertTrue(ProfilePromptStore.conversationSystemPrompt.contains("截至 YYYY-MM-DD"))
        assertTrue(ProfilePromptStore.compactionSystemPrompt.contains("item_range"))
    }

    @Test
    fun promptsRejectMachineResultsAndBatchScopedObservations() {
        assertTrue(ProfilePromptStore.systemPrompt.contains("本人原话门槛"))
        assertTrue(ProfilePromptStore.systemPrompt.contains("游戏结算"))
        assertTrue(ProfilePromptStore.conversationSystemPrompt.contains("三个月后"))
        assertTrue(ProfilePromptStore.conversationSystemPrompt.contains("连续命令"))
        assertTrue(ProfilePromptStore.compactionSystemPrompt.contains("reason=not_profile"))
        assertTrue(ProfilePromptStore.compactionSystemPrompt.contains("完成150次钓鱼"))
        assertTrue(ProfilePromptStore.systemPrompt.contains("测试或投毒"))
        assertTrue(ProfilePromptStore.conversationSystemPrompt.contains("单次矛盾发言"))
        assertTrue(ProfilePromptStore.systemPrompt.contains("supports<=1"))
        assertTrue(ProfilePromptStore.conversationSystemPrompt.contains("supports>=2"))
        assertTrue(ProfilePromptStore.systemPrompt.contains("候选修正"))
        assertTrue(ProfilePromptStore.compactionSystemPrompt.contains("待决状态"))
    }

    @Test
    fun promptsDefineSummaryAsAWholeProfileSynthesis() {
        assertTrue(ProfilePromptStore.systemPrompt.contains("不是本批聊天摘要"))
        assertTrue(ProfilePromptStore.conversationSystemPrompt.contains("不是本批聊天摘要"))
        assertTrue(ProfilePromptStore.compactionSystemPrompt.contains("全部保留条目"))
        assertTrue(ProfilePromptStore.compactionSystemPrompt.contains("不得只描述最后编辑的条目"))
        assertTrue(ProfilePromptStore.compactionSystemPrompt.contains("即使没有条目操作也应重写"))
    }

    @Test
    fun removesRelatedGroupAliasFromRewrittenRelationship() {
        val relation = item(
            id = "relation-item",
            category = ProfileCategory.RELATIONSHIP_NOTE,
            content = "经常讨论技术方案",
            confidence = ProfileConfidence.LOW,
            firstSeenAt = 10,
        ).copy(relatedUserId = 200)
        val profile = profile().copy(items = listOf(relation))

        val plan = UserProfileCompactor.reduce(
            current = profile,
            supportStats = emptyMap(),
            response = ProfileCompactionResponse(
                rewrites = listOf(
                    ProfileCompactionRewrite("P1", "经常与 R1 讨论技术方案并互相提供建议")
                ),
                summary = profile.summary,
            ),
            model = "test-model",
            promptVersion = "compact-v1",
            summaryMaxLength = 500,
        )

        assertEquals("经常与对方讨论技术方案并互相提供建议", plan.reduction.profile.items.single().content)
        assertFalse(plan.reduction.profile.items.single().content.contains("R1"))
    }

    @Test
    fun ignoresNoopRewriteWithoutChangingVersion() {
        val profile = profile()
        val plan = UserProfileCompactor.reduce(
            current = profile,
            supportStats = emptyMap(),
            response = ProfileCompactionResponse(
                rewrites = listOf(
                    ProfileCompactionRewrite("P1", "关注 Qwen、DeepSeek 等模型")
                )
            ),
            model = "test-model",
            promptVersion = "compact-v1",
            summaryMaxLength = 500,
        )

        assertTrue(plan.reduction.operations.isEmpty())
        assertEquals(0, plan.rewrittenItems)
        assertEquals(profile.version, plan.reduction.profile.version)
    }

    @Test
    fun allowsSummaryOnlyCompactionAcrossTheWholeProfile() {
        val profile = profile().copy(summary = "最近一次只讨论了 C#。")
        val plan = UserProfileCompactor.reduce(
            current = profile,
            supportStats = emptyMap(),
            response = ProfileCompactionResponse(
                summary = "该用户从事上位机开发，关注大语言模型，表达直接。",
            ),
            model = "test-model",
            promptVersion = "compact-v3",
            summaryMaxLength = 500,
        )

        assertTrue(plan.reduction.operations.isEmpty())
        assertEquals(profile.version + 1, plan.reduction.profile.version)
        assertEquals(
            "该用户从事上位机开发，关注大语言模型，表达直接。",
            plan.reduction.profile.summary,
        )
    }

    @Test
    fun appliesValidSummaryEvenWhenSomeOperationsAreSkipped() {
        val profile = profile()
        val plan = UserProfileCompactor.reduce(
            current = profile,
            supportStats = mapOf("fact-item" to supportStats(1)),
            response = ProfileCompactionResponse(
                deletes = listOf(
                    ProfileCompactionDelete("P4", ProfileCompactionDeleteReason.OVER_SPECIFIC)
                ),
                summary = "该用户从事上位机开发，并关注大语言模型。",
            ),
            model = "test-model",
            promptVersion = "compact-v4",
            summaryMaxLength = 500,
        )

        assertTrue(plan.reduction.operations.isEmpty())
        assertTrue(plan.skippedOperations.single().contains("high"))
        assertEquals(profile.version + 1, plan.reduction.profile.version)
        assertEquals("该用户从事上位机开发，并关注大语言模型。", plan.reduction.profile.summary)
    }

    @Test
    fun repairsInvertedItemRangesEvenWithoutModelOperations() {
        val current = profile().copy(
            items = listOf(
                profile().items.first().copy(firstSeenAt = 300, lastConfirmedAt = 100)
            )
        )
        val plan = UserProfileCompactor.reduce(
            current = current,
            supportStats = emptyMap(),
            response = ProfileCompactionResponse(summary = current.summary),
            model = "test-model",
            promptVersion = "compact-v5",
            summaryMaxLength = 500,
        )

        assertEquals(1, plan.repairedItemRanges)
        assertEquals(100, plan.reduction.profile.items.single().firstSeenAt)
        assertEquals(300, plan.reduction.profile.items.single().lastConfirmedAt)
        assertEquals(current.version + 1, plan.reduction.profile.version)
    }

    @Test
    fun deletesRetainedBatchScopedItemsEvenWhenModelMissesThem() {
        val batchScoped = UserProfileItem(
            id = "batch-scoped",
            category = ProfileCategory.SOCIAL_MODE,
            content = "本批多次通过简短调侃参与群聊。",
            confidence = ProfileConfidence.HIGH,
            firstSeenAt = 100,
            lastConfirmedAt = 200,
        )
        val stable = UserProfileItem(
            id = "stable",
            category = ProfileCategory.NOTABLE_FACT,
            content = "2025-01-01提及正在准备研究生复试。",
            confidence = ProfileConfidence.MEDIUM,
            firstSeenAt = 100,
            lastConfirmedAt = 200,
        )
        val current = UserProfileSnapshot(
            userId = 1,
            groupId = 10,
            summary = "本批多次参与群聊。",
            version = 1,
            cursorTime = 100,
            snapshotEndTime = 200,
            items = listOf(batchScoped, stable),
        )

        val plan = UserProfileCompactor.reduce(
            current = current,
            supportStats = mapOf("batch-scoped" to supportStats(5)),
            response = ProfileCompactionResponse(summary = current.summary),
            model = "test-model",
            promptVersion = "compact-v5",
            summaryMaxLength = 500,
        )

        assertEquals(1, plan.deletedItems)
        assertFalse(plan.reduction.profile.items.any { it.id == "batch-scoped" })
        assertTrue(plan.reduction.profile.items.any { it.id == "stable" })
        assertFalse(ProfileContentRules.containsBatchScopedText(plan.reduction.profile.summary))
    }

    @Test
    fun processesMoreThanTwelveOperationsOfOneType() {
        val items = (1..15).map { index ->
            item(
                id = "item-$index",
                category = ProfileCategory.EXPRESSION_STYLE,
                content = "旧表达方式 $index",
                confidence = ProfileConfidence.LOW,
                firstSeenAt = index,
            )
        }
        val profile = profile().copy(items = items)
        val plan = UserProfileCompactor.reduce(
            current = profile,
            supportStats = emptyMap(),
            response = ProfileCompactionResponse(
                rewrites = items.indices.map { index ->
                    ProfileCompactionRewrite("P${index + 1}", "概括后的表达方式 ${index + 1}")
                },
                summary = profile.summary,
            ),
            model = "test-model",
            promptVersion = "compact-v1",
            summaryMaxLength = 500,
        )

        assertEquals(15, plan.rewrittenItems)
        assertTrue(plan.skippedOperations.isEmpty())
    }

    @Test
    fun keepsOldSummaryWhenNewSummaryContainsTemporaryReference() {
        val profile = profile()
        val plan = UserProfileCompactor.reduce(
            current = profile,
            supportStats = emptyMap(),
            response = ProfileCompactionResponse(
                rewrites = listOf(ProfileCompactionRewrite("P1", "关注多个国内外大语言模型")),
                summary = "P1 已经概括多个模型兴趣。",
            ),
            model = "test-model",
            promptVersion = "compact-v1",
            summaryMaxLength = 500,
        )

        assertEquals(profile.summary, plan.reduction.profile.summary)
        assertTrue(plan.skippedOperations.single().contains("临时画像条目编号"))
    }

    private fun delete(
        profile: UserProfileSnapshot,
        itemRef: String,
        stats: Map<String, ProfileItemSupportStats>,
    ) = UserProfileCompactor.reduce(
        current = profile,
        supportStats = stats,
        response = ProfileCompactionResponse(
            deletes = listOf(
                ProfileCompactionDelete(itemRef, ProfileCompactionDeleteReason.ONE_OFF)
            )
        ),
        model = "test-model",
        promptVersion = "compact-v1",
        summaryMaxLength = 500,
    )

    private fun profile() = UserProfileSnapshot(
        userId = 100,
        groupId = 10,
        summary = "关注模型并从事软件开发。",
        version = 8,
        cursorTime = 300,
        snapshotEndTime = 1_000,
        reliable = true,
        items = listOf(
            item("model-a", ProfileCategory.INTEREST, "关注 Qwen、DeepSeek 等模型", ProfileConfidence.LOW, 10),
            item("model-b", ProfileCategory.INTEREST, "关注开源大模型部署和性能", ProfileConfidence.MEDIUM, 20),
            item("style-item", ProfileCategory.EXPRESSION_STYLE, "会使用“我去”表示惊讶", ProfileConfidence.LOW, 30),
            item("fact-item", ProfileCategory.NOTABLE_FACT, "从事上位机开发，使用 C#", ProfileConfidence.HIGH, 40),
        ),
    )

    private fun item(
        id: String,
        category: ProfileCategory,
        content: String,
        confidence: ProfileConfidence,
        firstSeenAt: Int,
    ) = UserProfileItem(
        id = id,
        category = category,
        content = content,
        confidence = confidence,
        firstSeenAt = firstSeenAt,
        lastConfirmedAt = firstSeenAt + 100,
    )

    private fun supportStats(count: Int) = ProfileItemSupportStats(count, 1, 2)
}
