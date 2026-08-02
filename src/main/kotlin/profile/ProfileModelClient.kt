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

class ProfileModelClient(
    private val endpoint: LargeLanguageModels.ProfileEndpoint,
) : ProfileModel, ConversationProfileModel {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    override val modelName: String
        get() = endpoint.model

    override suspend fun analyze(
        profile: UserProfileSnapshot,
        batch: ProfileHistoryBatch,
    ): ProfileModelResult {
        val content = StringBuilder()
        var lastUsage: Usage? = null
        var cacheUsage: ModelService.CacheUsage? = null
        endpoint.service.chatCompletions(
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
        ) { cacheUsage = it }.collect { chunk ->
            chunk.choices.firstOrNull()?.delta?.content?.let(content::append)
            chunk.usage?.let { lastUsage = it }
        }

        val raw = content.toString().replace(THINK_REGEX, "").trim()
        val response = parseResponse(raw)
        return ProfileModelResult(
            response = response,
            rawResponse = raw,
            usage = ProfileTokenUsage(
                promptTokens = lastUsage?.promptTokens ?: 0,
                completionTokens = lastUsage?.completionTokens ?: 0,
                cachedTokens = cacheUsage?.hitTokens ?: 0,
            ),
        )
    }

    override suspend fun analyzeConversation(
        profiles: Map<Long, UserProfileSnapshot>,
        batch: ConversationProfileBatch,
        eligibleUserIds: Set<Long>,
    ): ConversationProfileModelResult {
        val content = StringBuilder()
        var lastUsage: Usage? = null
        var cacheUsage: ModelService.CacheUsage? = null
        endpoint.service.chatCompletions(
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
        ) { cacheUsage = it }.collect { chunk ->
            chunk.choices.firstOrNull()?.delta?.content?.let(content::append)
            chunk.usage?.let { lastUsage = it }
        }

        val raw = content.toString().replace(THINK_REGEX, "").trim()
        return ConversationProfileModelResult(
            response = parseObject(raw),
            rawResponse = raw,
            usage = ProfileTokenUsage(
                promptTokens = lastUsage?.promptTokens ?: 0,
                completionTokens = lastUsage?.completionTokens ?: 0,
                cachedTokens = cacheUsage?.hitTokens ?: 0,
            ),
        )
    }

    private fun parseResponse(raw: String): ProfileModelResponse {
        return parseObject(raw)
    }

    private inline fun <reified T> parseObject(raw: String): T {
        val unfenced = raw
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val objectText = if (unfenced.startsWith('{') && unfenced.endsWith('}')) {
            unfenced
        } else {
            val start = unfenced.indexOf('{')
            val end = unfenced.lastIndexOf('}')
            if (start < 0 || end <= start) throw SerializationException("模型响应中没有完整 JSON object")
            unfenced.substring(start, end + 1)
        }
        return json.decodeFromString(objectText)
    }

    companion object {
        private val THINK_REGEX = Regex("<think>[\\s\\S]*?</think>")
    }
}
