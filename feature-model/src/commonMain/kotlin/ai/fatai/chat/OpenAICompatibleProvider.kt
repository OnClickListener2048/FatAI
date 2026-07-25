package ai.fatai.chat

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OpenAICompatibleProvider(
    override val type: ProviderType,
    private val client: HttpClient
) : ChatProvider {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
    private val jsonUtf8ContentType = ContentType.Application.Json.withCharset(Charsets.UTF_8)

    private fun chatCompletionsUrl(baseUrl: String) = "${baseUrl.trimEnd('/')}/chat/completions"

    override suspend fun chat(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<ChatStreamChunk> = flow {
        val request = OpenAIRequest(
            model = config.model,
            messages = messages.map { OpenAIMessage(role = it.role, content = it.content) },
            stream = true,
            max_tokens = config.maxTokens,
            temperature = config.temperature,
            top_p = config.topP
        )

        val response = client.post(chatCompletionsUrl(config.baseUrl)) {
            method = HttpMethod.Post
            header("Authorization", "Bearer ${config.apiKey}")
            accept(ContentType.Text.EventStream)
            contentType(jsonUtf8ContentType)
            setBody(json.encodeToString(request))
            timeout {
                requestTimeoutMillis = 120_000L
                connectTimeoutMillis = 30_000L
                socketTimeoutMillis = 120_000L
            }
        }

        if (!response.status.isSuccess()) {
            error("Chat request failed with ${response.status}: ${response.bodyAsText()}")
        }

        if (response.contentType()?.match(ContentType.Application.Json) == true) {
            val result = json.decodeFromString<OpenAIResponse>(response.bodyAsText())
            emit(ChatStreamChunk(
                content = result.choices?.firstOrNull()?.message?.content.orEmpty(),
                isDone = true,
                finishReason = result.choices?.firstOrNull()?.finish_reason
            ))
        } else if (response.contentType()?.match(ContentType.Text.EventStream) == true) {
            val channel = response.bodyAsChannel()
            var streamCompleted = false
            while (!streamCompleted) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trimStart()
                if (data == "[DONE]") {
                    streamCompleted = true
                    emit(ChatStreamChunk(content = "", isDone = true))
                    continue
                }
                try {
                    val response = json.decodeFromString<OpenAIStreamResponse>(data)
                    val delta = response.choices?.firstOrNull()?.delta
                    val content = delta?.content ?: ""
                    val finishReason = response.choices?.firstOrNull()?.finish_reason
                    if (finishReason != null) streamCompleted = true
                    emit(ChatStreamChunk(
                        content = content,
                        isDone = finishReason != null,
                        finishReason = finishReason
                    ))
                } catch (_: Exception) { }
            }
            if (!streamCompleted) emit(ChatStreamChunk(content = "", isDone = true))
        } else {
            error("Unsupported chat response Content-Type: ${response.contentType()}")
        }
    }

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
            }
            val body = response.bodyAsText()
            val result = json.decodeFromString<OpenAIResponse>(body)
            Result.success(result.choices?.firstOrNull()?.message?.content ?: "")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

@Serializable data class OpenAIRequest(val model: String, val messages: List<OpenAIMessage>, val stream: Boolean = true, val max_tokens: Int = 4096, val temperature: Float = 0.7f, val top_p: Float = 1.0f)
@Serializable data class OpenAIMessage(val role: String, val content: String)
@Serializable data class OpenAIStreamResponse(val choices: List<OpenAIStreamChoice>? = null, val id: String? = null, val model: String? = null)
@Serializable data class OpenAIStreamChoice(val delta: OpenAIDelta? = null, val finish_reason: String? = null, val index: Int? = null)
@Serializable data class OpenAIDelta(val content: String? = null, val role: String? = null)
@Serializable data class OpenAIResponse(val choices: List<OpenAIResponseChoice>? = null, val id: String? = null, val model: String? = null, val usage: OpenAIUsage? = null)
@Serializable data class OpenAIResponseChoice(val message: OpenAIMessage? = null, val finish_reason: String? = null)
@Serializable data class OpenAIUsage(val prompt_tokens: Int = 0, val completion_tokens: Int = 0, val total_tokens: Int = 0)
