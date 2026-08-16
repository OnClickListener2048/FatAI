package ai.fatai.chat

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Direct OpenAI-compatible endpoint client for synchronous, non-streaming calls only.
 *
 * Chat turns never reach a provider directly — they stream through the FatAI server, which
 * owns the tools. This provider is the local-model path: [ai.fatai.feature.model.HttpLocalModelEngine]
 * uses [chatSync] for on-device memory extraction and title generation.
 */
class OpenAICompatibleProvider(
    override val type: ProviderType,
    private val client: HttpClient
) : ChatProvider {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val jsonUtf8ContentType = ContentType.Application.Json.withCharset(Charsets.UTF_8)

    override suspend fun chatSync(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Result<String> {
        return try {
            val request = OpenAIRequest(
                model = config.model,
                messages = messages.map { OpenAIMessage(role = it.role, content = it.content) },
                stream = false,
                max_tokens = config.maxTokens,
                temperature = config.temperature,
                top_p = config.topP
            )
            val response = client.post(chatCompletionsUrl(config.baseUrl)) {
                header("Authorization", "Bearer ${config.apiKey}")
                contentType(jsonUtf8ContentType)
                setBody(json.encodeToString(request))
                timeout {
                    // The shared client on JVM disables request timeouts for SSE; a hanging local
                    // service must not stall the caller (memory extraction / title generation)
                    // indefinitely, so bound the non-streaming call explicitly.
                    requestTimeoutMillis = 30_000L
                    connectTimeoutMillis = 10_000L
                    socketTimeoutMillis = 30_000L
                }
            }
            val body = response.bodyAsText()
            val result = json.decodeFromString<OpenAIResponse>(body)
            Result.success(result.choices?.firstOrNull()?.message?.content ?: "")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun chatCompletionsUrl(baseUrl: String) = "${baseUrl.trimEnd('/')}/chat/completions"
}

@Serializable data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val stream: Boolean = true,
    val max_tokens: Int = 4096,
    val temperature: Float = 0.7f,
    val top_p: Float = 1.0f
)
@Serializable data class OpenAIMessage(
    val role: String,
    val content: String? = null
)
@Serializable data class OpenAIResponse(val choices: List<OpenAIResponseChoice>? = null, val id: String? = null, val model: String? = null, val usage: OpenAIUsage? = null)
@Serializable data class OpenAIResponseChoice(val message: OpenAIMessage? = null, val finish_reason: String? = null)
@Serializable data class OpenAIUsage(val prompt_tokens: Int = 0, val completion_tokens: Int = 0, val total_tokens: Int = 0)
