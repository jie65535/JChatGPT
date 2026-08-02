package top.jie65535.mirai.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.message.data.source
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.config.PluginConfig
import top.jie65535.mirai.data.ChatHistoryStore
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

object ProfileAutoMaintenance {
    private data class PendingConversation(
        val generation: Long,
        val groupId: Long,
        val botId: Long,
        val startTime: Int,
        val endTime: Int,
        val lastActivityAt: Int,
    )

    private val lock = Any()
    private val generation = AtomicLong()
    private val pending = mutableMapOf<Long, PendingConversation>()

    fun recordCompletedConversation(event: GroupMessageEvent, lastActivityAt: Int) {
        if (!PluginConfig.profileEnabled || !PluginConfig.profileAutoUpdateEnabled) return
        if (!UserProfileStore.isAvailable || !ChatHistoryStore.isAvailable) return

        val subjectId = event.subject.id
        val now = currentEpochSecond()
        val currentGeneration = generation.incrementAndGet()
        val triggerTime = event.message.source.time
        val initialStart = (triggerTime.toLong() - PluginConfig.historyWindowMin.coerceAtLeast(0) * 60L)
            .coerceAtLeast(0)
            .toInt()
        val conversation = synchronized(lock) {
            val previous = pending[subjectId]
            PendingConversation(
                generation = currentGeneration,
                groupId = event.group.id,
                botId = event.bot.id,
                startTime = minOf(previous?.startTime ?: initialStart, initialStart),
                endTime = maxOf(previous?.endTime ?: now.safeNextSecond(), now.safeNextSecond()),
                lastActivityAt = maxOf(previous?.lastActivityAt ?: lastActivityAt, lastActivityAt),
            ).also { pending[subjectId] = it }
        }

        val ttlSeconds = PluginConfig.contextCacheTimeoutMinutes.coerceAtLeast(1) * 60
        JChatGPT.launch {
            val remainingSeconds = conversation.lastActivityAt.toLong() + ttlSeconds - currentEpochSecond()
            if (remainingSeconds > 0) delay(remainingSeconds.seconds)
            val closed = synchronized(lock) {
                pending[subjectId]
                    ?.takeIf { it.generation == conversation.generation }
                    ?.copy(endTime = currentEpochSecond().safeNextSecond())
                    ?.also { pending.remove(subjectId) }
            } ?: return@launch
            process(closed)
        }
    }

    fun clear() {
        synchronized(lock) { pending.clear() }
    }

    private suspend fun process(conversation: PendingConversation) {
        try {
            val report = UserProfileAnalysisService.analyzeConversation(
                botId = conversation.botId,
                groupId = conversation.groupId,
                startTime = conversation.startTime,
                endTime = conversation.endTime,
                minAuthoredTextChars = PluginConfig.profileAutoMinAuthoredTextChars,
            ) ?: return
            JChatGPT.logger.info(
                "PROFILE_AUTO group=${conversation.groupId} users=${report.analyzedUsers} " +
                        "messages=${report.processedMessages} operations=${report.appliedOperations} " +
                        "skipped=${report.skippedOperations} " +
                        "tokens=${report.usage.promptTokens}/${report.usage.completionTokens}"
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            JChatGPT.logger.warning(
                "自动画像维护失败: group=${conversation.groupId}, " +
                        "range=[${conversation.startTime}, ${conversation.endTime})",
                cause,
            )
        }
    }

    private fun currentEpochSecond(): Int =
        (System.currentTimeMillis() / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun Int.safeNextSecond(): Int = if (this == Int.MAX_VALUE) this else this + 1
}
