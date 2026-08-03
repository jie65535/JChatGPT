package top.jie65535.mirai.profile

import java.util.concurrent.atomic.AtomicLong

class ProfileAnalysisRunToken internal constructor(
    internal val generation: Long,
)

data class ProfileAnalysisStopReport(
    val userTasks: Int,
    val groupTasks: Int,
    val compactionTasks: Int,
) {
    val totalTasks: Int
        get() = userTasks + groupTasks + compactionTasks
}

internal class ProfileAnalysisRunGate {
    private val generation = AtomicLong()

    fun newToken(): ProfileAnalysisRunToken = ProfileAnalysisRunToken(generation.get())

    fun stopCurrentRuns() {
        generation.incrementAndGet()
    }

    fun canContinue(token: ProfileAnalysisRunToken): Boolean = token.generation == generation.get()
}
