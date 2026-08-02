package top.jie65535.mirai.profile

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.jie65535.mirai.llm.LargeLanguageModels
import top.jie65535.mirai.llm.ModelService
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class ConversationProfileLiveExperiment {
    @Test
    fun analyzeConfiguredHistoricalConversation() = runBlocking {
        val apiKey = System.getenv("PROFILE_EXPERIMENT_API_KEY") ?: return@runBlocking
        val source = File(checkNotNull(System.getenv("PROFILE_EXPERIMENT_SOURCE")))
        val botId = checkNotNull(System.getenv("PROFILE_EXPERIMENT_BOT_ID")).toLong()
        val groupId = checkNotNull(System.getenv("PROFILE_EXPERIMENT_GROUP_ID")).toLong()
        val startTime = checkNotNull(System.getenv("PROFILE_EXPERIMENT_START")).toInt()
        val endTime = checkNotNull(System.getenv("PROFILE_EXPERIMENT_END")).toInt()
        val output = File(checkNotNull(System.getenv("PROFILE_EXPERIMENT_OUTPUT")))
        val modelName = checkNotNull(System.getenv("PROFILE_EXPERIMENT_MODEL"))
        val baseUrl = checkNotNull(System.getenv("PROFILE_EXPERIMENT_API"))
        val messageLimit = System.getenv("PROFILE_EXPERIMENT_MESSAGE_LIMIT")?.toInt() ?: 150
        val firstChunkTimeout = System.getenv("PROFILE_EXPERIMENT_FIRST_CHUNK_TIMEOUT")?.toInt() ?: 240

        val batch = checkNotNull(
            ProfileHistoryReader(source).loadConversationBatch(
                botId = botId,
                groupId = groupId,
                startTime = startTime,
                endTime = endTime,
                messageLimit = messageLimit,
                maxMessageChars = 1_000,
            )
        )
        val eligible = batch.authoredTextCharsByUser.filterValues { it >= 20 }.keys
        check(eligible.isNotEmpty()) { "所选会话没有达到文本门槛的参与者" }
        val profiles = eligible.associateWith { userId ->
            UserProfileSnapshot(userId = userId, cursorTime = 0, snapshotEndTime = 0)
        }
        val service = ModelService(
            baseUrl = baseUrl,
            token = apiKey,
            timeout = maxOf(180, firstChunkTimeout).seconds,
            firstChunkTimeout = firstChunkTimeout.seconds,
        )
        try {
            val client = ProfileModelClient(
                LargeLanguageModels.ProfileEndpoint(service, modelName, temperature = 0.1)
            )
            val result = client.analyzeConversation(profiles, batch, eligible)
            val reductions = ConversationProfileReducer.reduce(
                profiles = profiles,
                batch = batch,
                eligibleUserIds = eligible,
                response = result.response,
                model = modelName,
                promptVersion = ProfilePromptStore.PROMPT_VERSION,
                summaryMaxLength = 500,
            )
            val names = batch.aliases.mapValues { (userId, alias) -> "$alias/$userId" }
            val injection = UserProfileContextRenderer.render(
                profiles = reductions.map { it.profile },
                favorabilityByUserId = emptyMap(),
                displayNames = names,
                activeUserIds = eligible,
                summaryMaxChars = 300,
            )

            output.parentFile?.mkdirs()
            output.writeText(
                buildString {
                    appendLine("# 多人会话画像真实数据实验")
                    appendLine()
                    appendLine("- group: $groupId")
                    appendLine("- range: [$startTime, $endTime)")
                    appendLine("- messages: ${batch.messages.size}")
                    appendLine("- eligible_users: ${eligible.size}")
                    appendLine("- operations: ${reductions.sumOf { it.operations.size }}")
                    appendLine("- tokens: ${result.usage.promptTokens}/${result.usage.completionTokens}, cached=${result.usage.cachedTokens}")
                    appendLine()
                    appendLine("## 模型结构化输出")
                    appendLine()
                    appendLine("```json")
                    appendLine(prettyJson.encodeToString(result.response))
                    appendLine("```")
                    appendLine()
                    appendLine("## 证据核对")
                    reductions.flatMap { reduction ->
                        reduction.operations.map { operation -> reduction.profile.userId to operation }
                    }.forEach { (userId, operation) ->
                        appendLine()
                        appendLine("- ${batch.aliases[userId]}/$userId: ${operation.action} ${operation.category} ${operation.content}")
                        operation.evidenceRefs.forEach { ref ->
                            val evidence = checkNotNull(batch.evidenceByRef[ref])
                            append("  - [e:").append(ref).append("][")
                                .append(batch.aliases[evidence.record.fromId]).append("] ")
                                .appendLine(evidence.text.replace('\n', ' '))
                        }
                    }
                    appendLine()
                    appendLine("## 实际注入文本")
                    appendLine()
                    appendLine("```")
                    append(injection)
                    appendLine("```")
                },
                Charsets.UTF_8,
            )
        } finally {
            service.httpClient.close()
        }
    }

    companion object {
        private val prettyJson = Json { prettyPrint = true }
    }
}
