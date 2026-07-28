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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ai.fatai.feature.tools.OpenAICompatibleToolAdapter
import ai.fatai.feature.tools.ProviderToolCall
import ai.fatai.feature.tools.ToolDefinition

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

    private fun chatCompletionsUrl(baseUrl: String) = "${baseUrl.trimEnd('/')}/chat/completions"

    override suspend fun chat(
        messages: List<ChatMessage>,
        config: ProviderConfig,
        tools: List<ToolDefinition>
    ): Flow<ChatStreamChunk> = flow {
        val toolPayload = OpenAICompatibleToolAdapter.encode(tools)
        val request = OpenAIRequest(
            model = config.model,
            messages = messages.map { OpenAIMessage(role = it.role, content = it.content) },
            stream = true,
            max_tokens = config.maxTokens,
            temperature = config.temperature,
            top_p = config.topP,
            tools = toolPayload.value as? JsonArray,
            tool_choice = if (tools.isEmpty()) null else "auto",
            // DeepSeek enables its reasoning stream by default. Disable it so the
            // response starts with ordinary `content` chunks.
            thinking = if (config.providerType == ProviderType.DeepSeek) OpenAIThinking(type = "disabled") else null
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
            val message = result.choices?.firstOrNull()?.message
            emit(ChatStreamChunk(
                content = message?.content.orEmpty(),
                isDone = true,
                finishReason = result.choices?.firstOrNull()?.finish_reason,
                toolCalls = message?.tool_calls.orEmpty().mapNotNull(OpenAIResponseToolCall::toProviderCall)
            ))
        } else if (response.contentType()?.match(ContentType.Text.EventStream) == true) {
            val channel = response.bodyAsChannel()
            var streamCompleted = false
            val streamedToolCalls = mutableMapOf<Int, StreamedToolCall>()
            while (!streamCompleted) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trimStart()
                if (data == "[DONE]") {
                    streamCompleted = true
                    emit(ChatStreamChunk(
                        content = "",
                        isDone = true,
                        toolCalls = streamedToolCalls.toProviderCalls()
                    ))
                    continue
                }
                try {
                    val response = json.decodeFromString<OpenAIStreamResponse>(data)
                    val delta = response.choices?.firstOrNull()?.delta
                    // Reasoning-capable OpenAI-compatible models may stream their first
                    // tokens as `reasoning_content`, `reasoning`, or `text` instead of
                    // `content`. Forward them immediately so the UI can update per chunk.
                    val content = delta?.streamContent().orEmpty()
                    delta?.tool_calls.orEmpty().forEach { toolCall ->
                        val accumulated = streamedToolCalls.getOrPut(toolCall.index) { StreamedToolCall() }
                        toolCall.id?.let { accumulated.id = it }
                        toolCall.function?.name?.let { accumulated.name = it }
                        toolCall.function?.arguments?.let(accumulated.arguments::append)
                    }
                    val finishReason = response.choices?.firstOrNull()?.finish_reason
                    if (finishReason != null) streamCompleted = true
                    emit(ChatStreamChunk(
                        content = content,
                        isDone = finishReason != null,
                        finishReason = finishReason,
                        toolCalls = if (finishReason == "tool_calls") streamedToolCalls.toProviderCalls() else emptyList()
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
        config: ProviderConfig,
        tools: List<ToolDefinition>
    ): Result<String> {
        return try {
            val toolPayload = OpenAICompatibleToolAdapter.encode(tools)
            val request = OpenAIRequest(
                model = config.model,
                messages = messages.map { OpenAIMessage(role = it.role, content = it.content) },
                stream = false,
                max_tokens = config.maxTokens,
                temperature = config.temperature,
                top_p = config.topP,
                tools = toolPayload.value as? JsonArray,
                tool_choice = if (tools.isEmpty()) null else "auto"
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

@Serializable data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val stream: Boolean = true,
    val max_tokens: Int = 4096,
    val temperature: Float = 0.7f,
    val top_p: Float = 1.0f,
    val tools: JsonArray? = null,
    val tool_choice: String? = null,
    val thinking: OpenAIThinking? = null
)
@Serializable data class OpenAIThinking(val type: String)
@Serializable data class OpenAIMessage(
    val role: String,
    val content: String? = null,
    val tool_calls: List<OpenAIResponseToolCall>? = null,
    val tool_call_id: String? = null
)
@Serializable data class OpenAIStreamResponse(val choices: List<OpenAIStreamChoice>? = null, val id: String? = null, val model: String? = null)
@Serializable data class OpenAIStreamChoice(val delta: OpenAIDelta? = null, val finish_reason: String? = null, val index: Int? = null)
@Serializable data class OpenAIDelta(
    val content: String? = null,
    val reasoning_content: String? = null,
    val reasoning: String? = null,
    val text: String? = null,
    val role: String? = null,
    val tool_calls: List<OpenAIStreamToolCall>? = null
)
@Serializable data class OpenAIStreamToolCall(
    val index: Int = 0,
    val id: String? = null,
    val function: OpenAIToolFunction? = null
)
@Serializable data class OpenAIToolFunction(val name: String? = null, val arguments: String? = null)

private fun OpenAIDelta.streamContent(): String? = listOf(
    content,
    reasoning_content,
    reasoning,
    text
).firstOrNull { !it.isNullOrEmpty() }
@Serializable data class OpenAIResponse(val choices: List<OpenAIResponseChoice>? = null, val id: String? = null, val model: String? = null, val usage: OpenAIUsage? = null)
@Serializable data class OpenAIResponseChoice(val message: OpenAIMessage? = null, val finish_reason: String? = null)
@Serializable data class OpenAIResponseToolCall(
    val id: String? = null,
    val function: OpenAIToolFunction? = null
)
@Serializable data class OpenAIUsage(val prompt_tokens: Int = 0, val completion_tokens: Int = 0, val total_tokens: Int = 0)

private class StreamedToolCall {
    var id: String? = null
    var name: String? = null
    val arguments = StringBuilder()
}

private fun Map<Int, StreamedToolCall>.toProviderCalls(): List<ProviderToolCall> = values.mapNotNull { call ->
    val name = call.name ?: return@mapNotNull null
    ProviderToolCall(
        id = call.id,
        name = name,
        arguments = parseToolArguments(call.arguments.toString())
    )
}

private fun OpenAIResponseToolCall.toProviderCall(): ProviderToolCall? {
    val name = function?.name ?: return null
    return ProviderToolCall(id = id, name = name, arguments = parseToolArguments(function.arguments.orEmpty()))
}

private fun parseToolArguments(rawArguments: String): Map<String, String> = try {
    Json.parseToJsonElement(rawArguments).jsonObject.entries.associate { (key, value) ->
        key to (value.jsonPrimitive.contentOrNull ?: value.toString())
    }
} catch (_: Exception) {
    emptyMap()
}
