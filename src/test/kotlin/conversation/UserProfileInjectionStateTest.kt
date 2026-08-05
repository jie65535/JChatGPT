package top.jie65535.mirai.conversation

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class UserProfileInjectionStateTest {
    @Test
    fun injectsVisibleEntriesOnce() {
        val state = UserProfileInjectionState()
        val entries = mapOf(
            100L to "- 小明(100) | 好感度+3\n",
            200L to "- 小王(200) | 画像认识：熟悉 Kotlin\n",
        )

        val initial = state.renderChanges(entries.keys, entries, "你对相关群友的认识")

        assertContains(initial, "## 你对相关群友的认识")
        assertContains(initial, entries.getValue(100L).trim())
        assertContains(initial, entries.getValue(200L).trim())
        assertEquals("", state.renderChanges(entries.keys, entries, "你对相关群友的认识"))
    }

    @Test
    fun emitsOnlyNewAndChangedUsers() {
        val state = UserProfileInjectionState()
        state.renderChanges(
            candidateUserIds = listOf(100L, 200L),
            renderedEntries = mapOf(
                100L to "- 小明(100) | 好感度+3\n",
                200L to "- 小王(200) | 好感度+1\n",
            ),
            sectionTitle = "你对相关群友的认识",
        )

        val update = state.renderChanges(
            candidateUserIds = listOf(100L, 200L, 300L),
            renderedEntries = mapOf(
                100L to "- 小明(100) | 好感度+3\n",
                200L to "- 小王(200) | 好感度+5\n",
                300L to "- 小李(300) | 主观印象：表达直接\n",
            ),
            sectionTitle = "你对相关群友的认识",
        )

        assertContains(update, "## 你对相关群友的认识（更新）")
        assertContains(update, "小王(200) | 好感度+5")
        assertContains(update, "小李(300) | 主观印象：表达直接")
        assertFalse(update.contains("小明(100)"))
    }

    @Test
    fun explicitlyClearsPreviouslyVisibleContext() {
        val state = UserProfileInjectionState()
        state.renderChanges(
            candidateUserIds = listOf(100L),
            renderedEntries = mapOf(100L to "- 小明(100) | 好感度-2\n"),
            sectionTitle = "你对相关群友的认识",
        )

        val update = state.renderChanges(
            candidateUserIds = listOf(100L),
            renderedEntries = emptyMap(),
            sectionTitle = "你对相关群友的认识",
        )

        assertContains(update, "用户(100)")
        assertContains(update, "请忽略此前对应信息")
        assertEquals(
            "",
            state.renderChanges(listOf(100L), emptyMap(), "你对相关群友的认识"),
        )
    }

    @Test
    fun delaysGuidanceUntilAnInvisibleUserGetsContext() {
        val state = UserProfileInjectionState()

        assertEquals(
            "",
            state.renderChanges(listOf(100L), emptyMap(), "你对对方的认识"),
        )

        val firstVisible = state.renderChanges(
            candidateUserIds = listOf(100L),
            renderedEntries = mapOf(100L to "- 小明(100) | 画像认识：熟悉数据库\n"),
            sectionTitle = "你对对方的认识",
        )

        assertContains(firstVisible, "## 你对对方的认识\n")
        assertFalse(firstVisible.contains("（更新）"))
        assertContains(firstVisible, "仅在当前话题相关时自然运用")
    }
}
