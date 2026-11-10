package top.jie65535.mirai.data

import com.aallam.openai.api.core.Usage
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageEvent
import top.jie65535.mirai.llm.ModelService
import java.time.OffsetDateTime

data class ModelUsageAttribution(
    val botId: Long = 0,
    val userId: Long = 0,
    val userNickname: String = "",
    val groupId: Long? = null,
    val groupName: String? = null,
) {
    companion object {
        fun from(event: MessageEvent): ModelUsageAttribution {
            val group = (event as? GroupMessageEvent)?.group
            return ModelUsageAttribution(
                botId = event.bot.id,
                userId = event.sender.id,
                userNickname = event.senderName,
                groupId = group?.id,
                groupName = group?.name,
            )
        }
    }
}

object ModelUsageRecorder {
    fun recordTokens(
        event: MessageEvent,
        endpointLabel: String,
        modelAlias: String,
        provider: String,
        model: String,
        usageKind: String,
        usage: Usage?,
        cacheUsage: ModelService.UsageDetails? = null,
    ) {
        usage ?: return
        val promptTokens = usage.promptTokens ?: 0
        val completionTokens = usage.completionTokens ?: 0
        recordTokenValues(
            attribution = ModelUsageAttribution.from(event),
            endpointLabel = endpointLabel,
            modelAlias = modelAlias,
            provider = provider,
            model = model,
            usageKind = usageKind,
            promptTokens = promptTokens.toLong(),
            completionTokens = completionTokens.toLong(),
            totalTokens = (usage.totalTokens ?: (promptTokens + completionTokens)).toLong(),
            cachedTokens = (cacheUsage?.hitTokens ?: 0).toLong(),
        )
    }

    fun recordTokenValues(
        attribution: ModelUsageAttribution,
        endpointLabel: String,
        modelAlias: String,
        provider: String,
        model: String,
        usageKind: String,
        promptTokens: Long,
        completionTokens: Long,
        totalTokens: Long = promptTokens + completionTokens,
        cachedTokens: Long = 0,
    ) {
        record(
            attribution = attribution,
            endpointLabel = endpointLabel,
            modelAlias = modelAlias,
            provider = provider,
            model = model,
            usageKind = usageKind,
            unit = "tokens",
            inputUnits = promptTokens,
            outputUnits = completionTokens,
            totalUnits = totalTokens,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            cachedTokens = cachedTokens,
        )
    }

    fun recordUnits(
        event: MessageEvent,
        endpointLabel: String,
        modelAlias: String,
        provider: String,
        model: String,
        usageKind: String,
        unit: String,
        inputUnits: Long = 0,
        outputUnits: Long = 0,
        totalUnits: Long = inputUnits + outputUnits,
    ) {
        record(
            attribution = ModelUsageAttribution.from(event),
            endpointLabel = endpointLabel,
            modelAlias = modelAlias,
            provider = provider,
            model = model,
            usageKind = usageKind,
            unit = unit,
            inputUnits = inputUnits,
            outputUnits = outputUnits,
            totalUnits = totalUnits,
        )
    }

    private fun record(
        attribution: ModelUsageAttribution,
        endpointLabel: String,
        modelAlias: String,
        provider: String,
        model: String,
        usageKind: String,
        unit: String,
        inputUnits: Long,
        outputUnits: Long,
        totalUnits: Long,
        promptTokens: Long = 0,
        completionTokens: Long = 0,
        totalTokens: Long = 0,
        cachedTokens: Long = 0,
    ) {
        TokenUsageStore.recordUsage(
            ModelUsageEvent(
                timestamp = OffsetDateTime.now().toEpochSecond(),
                botId = attribution.botId,
                userId = attribution.userId,
                userNickname = attribution.userNickname,
                groupId = attribution.groupId,
                groupName = attribution.groupName,
                endpointLabel = endpointLabel,
                modelAlias = modelAlias,
                provider = provider,
                model = model,
                usageKind = usageKind,
                unit = unit,
                inputUnits = inputUnits.coerceAtLeast(0),
                outputUnits = outputUnits.coerceAtLeast(0),
                totalUnits = totalUnits.coerceAtLeast(0),
                promptTokens = promptTokens.coerceAtLeast(0),
                completionTokens = completionTokens.coerceAtLeast(0),
                totalTokens = totalTokens.coerceAtLeast(0),
                cachedTokens = cachedTokens.coerceAtLeast(0),
            )
        )
    }
}
