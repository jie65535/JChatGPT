package top.jie65535.mirai.profile

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatResponseFormat
import com.aallam.openai.api.chat.StreamOptions
import com.aallam.openai.api.core.Usage
import com.aallam.openai.api.model.ModelId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import top.jie65535.mirai.llm.LargeLanguageModels
import top.jie65535.mirai.llm.ModelService

internal val profileResponseJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

interface ProfileModel {
    val modelName: String

    suspend fun analyze(
        profile: UserProfileSnapshot,
        batch: ProfileHistoryBatch,
    ): ProfileModelResult
}

interface ConversationProfileModel {
    val modelName: String

    suspend fun analyzeConversation(
        profiles: Map<Long, UserProfileSnapshot>,
        batch: ConversationProfileBatch,
        eligibleUserIds: Set<Long>,
    ): ConversationProfileModelResult
}

interface ProfileCompactionModel {
    val modelName: String

    suspend fun compact(
        profile: UserProfileSnapshot,
        supportStats: Map<String, ProfileItemSupportStats>,
    ): ProfileCompactionModelResult
}

class ProfileModelClient(
    private val endpoint: LargeLanguageModels.ProfileEndpoint,
) : ProfileModel, ConversationProfileModel, ProfileCompactionModel {
    private val json = profileResponseJson

    override val modelName: String
        get() = endpoint.model

    override suspend fun analyze(
        profile: UserProfileSnapshot,
        batch: ProfileHistoryBatch,
    ): ProfileModelResult {
        val completion = complete(
            ChatCompletionRequest(
                model = ModelId(endpoint.model),
                temperature = endpoint.temperature,
                responseFormat = ChatResponseFormat.JsonObject,
                streamOptions = StreamOptions(includeUsage = true),
                messages = listOf(
                    ChatMessage.System(ProfilePromptStore.systemPrompt),
                    ChatMessage.User(ProfilePromptStore.buildUserPrompt(profile, batch)),
                ),
            )
        )
        val raw = completion.content.replace(THINK_REGEX, "").trim()
        val response = parseResponse(raw)
        return ProfileModelResult(
            response = response,
            rawResponse = raw,
            usage = completion.usage,
        )
    }

    override suspend fun analyzeConversation(
        profiles: Map<Long, UserProfileSnapshot>,
        batch: ConversationProfileBatch,
        eligibleUserIds: Set<Long>,
    ): ConversationProfileModelResult {
        val completion = complete(
            ChatCompletionRequest(
                model = ModelId(endpoint.model),
                temperature = endpoint.temperature,
                responseFormat = ChatResponseFormat.JsonObject,
                streamOptions = StreamOptions(includeUsage = true),
                messages = listOf(
                    ChatMessage.System(ProfilePromptStore.conversationSystemPrompt),
                    ChatMessage.User(
                        ProfilePromptStore.buildConversationUserPrompt(profiles, batch, eligibleUserIds)
                    ),
                ),
            )
        )
        val raw = completion.content.replace(THINK_REGEX, "").trim()
        return ConversationProfileModelResult(
            response = parseObject(raw),
            rawResponse = raw,
            usage = completion.usage,
        )
    }

    override suspend fun compact(
        profile: UserProfileSnapshot,
        supportStats: Map<String, ProfileItemSupportStats>,
    ): ProfileCompactionModelResult {
        val completion = complete(
            ChatCompletionRequest(
                model = ModelId(endpoint.model),
                temperature = endpoint.temperature,
                responseFormat = ChatResponseFormat.JsonObject,
                streamOptions = StreamOptions(includeUsage = true),
                messages = listOf(
                    ChatMessage.System(ProfilePromptStore.compactionSystemPrompt),
                    ChatMessage.User(ProfilePromptStore.buildCompactionUserPrompt(profile, supportStats)),
                ),
            )
        )
        val raw = completion.content.replace(THINK_REGEX, "").trim()
        return ProfileCompactionModelResult(
            response = json.decodeFromString(extractObject(raw)),
            rawResponse = raw,
            usage = completion.usage,
        )
    }

    private suspend fun complete(request: ChatCompletionRequest): CompletedProfileResponse {
        val content = StringBuilder()
        var lastUsage: Usage? = null
        var cacheUsage: ModelService.CacheUsage? = null
        endpoint.service.chatCompletions(request) { cacheUsage = it }.collect { chunk ->
            chunk.choices.firstOrNull()?.delta?.content?.let(content::append)
            chunk.usage?.let { lastUsage = it }
        }
        require(content.isNotBlank()) { "模型流式响应没有文本内容" }
        return CompletedProfileResponse(
            content = content.toString(),
            usage = lastUsage.toProfileUsage(cacheUsage),
        )
    }

    private fun Usage?.toProfileUsage(cacheUsage: ModelService.CacheUsage?) = ProfileTokenUsage(
        promptTokens = this?.promptTokens ?: 0,
        completionTokens = this?.completionTokens ?: 0,
        cachedTokens = cacheUsage?.hitTokens ?: 0,
    )

    private fun parseResponse(raw: String): ProfileModelResponse {
        return parseObject(raw)
    }

    private inline fun <reified T> parseObject(raw: String): T {
        return json.decodeFromString(extractObject(raw))
    }

    private fun extractObject(raw: String): String {
        val unfenced = raw
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        return if (unfenced.startsWith('{') && unfenced.endsWith('}')) {
            unfenced
        } else {
            val start = unfenced.indexOf('{')
            val end = unfenced.lastIndexOf('}')
            if (start < 0 || end <= start) throw SerializationException("模型响应中没有完整 JSON object")
            unfenced.substring(start, end + 1)
        }
    }

    companion object {
        private val THINK_REGEX = Regex("<think>[\\s\\S]*?</think>")
    }

    private data class CompletedProfileResponse(
        val content: String,
        val usage: ProfileTokenUsage,
    )
}
