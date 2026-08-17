package ai.fatai.feature.model

import ai.fatai.chat.ChatMessage
import ai.fatai.chat.ChatStreamChunk
import ai.fatai.chat.ChatUsage
import ai.fatai.chat.ProviderConfig
import ai.fatai.chat.ProviderToolCall
import ai.fatai.chat.ToolSource
import io.ktor.client.HttpClient
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

const val DEFAULT_FAT_AI_SERVER_URL = "http://127.0.0.1:8080"

/**
 * Streams model output from the FatAI FastAPI backend.
 *
 * Provider credentials, tool definitions, and tool execution are all server-owned; the client
 * sends only context hints and file references, and renders content, reasoning, and tool-call
 * provenance (sources) from the server's SSE events.
 */
class FatAiServerModelGateway(
    private val client: HttpClient,
    private val serverSync: FatAiServerSync,
    private val serverUrl: String = DEFAULT_FAT_AI_SERVER_URL
) : ModelGateway {
    override suspend fun stream(
        messages: List<ChatMessage>,
        config: ProviderConfig,
        context: ChatContext
    ): Flow<ChatStreamChunk> = flow {
        val preStart = TimeSource.Monotonic.markNow()
        serverSync.awaitModelConfiguration(config.configurationId)
        val configWait = preStart.elapsedNow().inWholeMilliseconds
        val tokenStart = TimeSource.Monotonic.markNow()
        val accessToken = serverSync.accessToken()
        val tokenWait = tokenStart.elapsedNow().inWholeMilliseconds
        val streamStartedAt = TimeSource.Monotonic.markNow()
        var firstChunkAt: Long? = null
        println("PERF => pre(config_wait=${configWait}ms token_wait=${tokenWait}ms)")
        client.preparePost("${serverUrl.trimEnd('/')}/v1/chat/stream") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
            header("Authorization", "Bearer $accessToken")
            setBody(
                json.encodeToString(
                    ServerChatStreamRequest(
                        messages = messages,
                        model = config.model.ifBlank { null },
                        modelConfigurationId = config.configurationId,
                        temperature = config.temperature,
                        thinking = config.thinkingEnabled,
                        workspaceId = context.workspaceId,
                        conversationId = context.conversationId,
                        responseLanguageTag = context.responseLanguageTag,
                        documents = context.documents.map { document ->
                            ServerDocumentReference(
                                fileId = document.fileId,
                                displayName = document.displayName,
                                mimeType = document.mimeType
                            )
                        },
                        includeContextualReferences = context.includeContextualReferences,
                        userMessageId = context.userMessageId,
                        assistantMessageId = context.assistantMessageId
                    )
                )
            )
        }.execute { response ->
            if (!response.status.isSuccess()) {
                // Server errors are ApiError JSON ({code, message}); surface a readable message
                // instead of dumping the raw body into the error bubble.
                val body = response.bodyAsText()
                val apiError = runCatching { json.decodeFromString<ServerApiError>(body) }.getOrNull()
                val detail = when {
                    apiError != null && apiError.message != null -> apiError.code?.let { "$it: ${apiError.message}" } ?: apiError.message
                    body.isNotBlank() -> body
                    else -> "FatAI server returned ${response.status.value}."
                }
                throw IllegalStateException(detail)
            }

            var eventName: String? = null
            val toolCalls = mutableListOf<ProviderToolCall>()
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                when {
                    line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                    line.startsWith("data:") -> {
                        val now = streamStartedAt.elapsedNow().inWholeMilliseconds
                        if (firstChunkAt == null && eventName != null) firstChunkAt = now
                        if (eventName == "message") {
                            val payload = json.decodeFromString<ServerStreamEvent>(line.substringAfter(':').trim())
                            if (payload.reasoningContent.isNotEmpty()) {
                                emit(ChatStreamChunk(content = "", reasoningContent = payload.reasoningContent))
                            }
                            if (payload.content.isNotEmpty()) emit(ChatStreamChunk(content = payload.content))
                        } else if (eventName == "tool_call") {
                            val payload = json.decodeFromString<ServerToolCall>(line.substringAfter(':').trim())
                            val call = payload.toProviderToolCall()
                            toolCalls += call
                            // The server executes the tool itself; this chunk only surfaces the
                            // call for progress display and provenance.
                            emit(ChatStreamChunk(content = "", toolCalls = listOf(call)))
                        } else if (eventName == "done") {
                            val donePayload = json.decodeFromString<ServerDoneEvent>(line.substringAfter(':').trim())
                            val persisted = donePayload.persisted
                            val persistError = donePayload.persistError
                            if (!persisted) {
                                println("WARN: server persist failed: $persistError — client will enqueue via outbox")
                            }
                            println(
                                "PERF => first_event=${firstChunkAt ?: -1}ms " +
                                    "total=${now}ms tool_calls=${toolCalls.size} persisted=$persisted"
                            )
                            emit(
                                ChatStreamChunk(
                                    content = "",
                                    isDone = true,
                                    toolCalls = toolCalls.toList(),
                                    persisted = persisted,
                                    usage = donePayload.usage?.let {
                                        ChatUsage(it.promptTokens, it.completionTokens, it.totalTokens)
                                    }
                                )
                            )
                        }
                    }
                    line.isBlank() -> eventName = null
                }
            }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class ServerChatStreamRequest(
    val messages: List<ChatMessage>,
    val model: String? = null,
    @SerialName("model_configuration_id") val modelConfigurationId: String? = null,
    val temperature: Float,
    val thinking: Boolean = false,
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("response_language_tag") val responseLanguageTag: String? = null,
    @SerialName("documents") val documents: List<ServerDocumentReference> = emptyList(),
    @SerialName("include_contextual_references") val includeContextualReferences: Boolean = true,
    @SerialName("user_message_id") val userMessageId: String? = null,
    @SerialName("assistant_message_id") val assistantMessageId: String? = null
)

@Serializable
private data class ServerDocumentReference(
    @SerialName("file_id") val fileId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("mime_type") val mimeType: String
)

@Serializable
private data class ServerStreamEvent(
    val content: String = "",
    @SerialName("reasoning_content") val reasoningContent: String = ""
)

@Serializable
private data class ServerToolCall(
    val id: String? = null,
    val name: String,
    val arguments: Map<String, String> = emptyMap(),
    val sources: List<ServerToolSource> = emptyList()
) {
    fun toProviderToolCall() = ProviderToolCall(
        id = id,
        name = name,
        arguments = arguments,
        sources = sources.map { source -> ToolSource(label = source.title, url = source.url) }
    )
}

@Serializable
private data class ServerToolSource(
    val title: String,
    val url: String? = null
)

@Serializable
private data class ServerDoneEvent(
    val persisted: Boolean = true,
    @SerialName("persist_error") val persistError: String? = null,
    val usage: ServerUsage? = null
)

@Serializable
private data class ServerUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0
)

@Serializable
private data class ServerApiError(
    val code: String? = null,
    val message: String? = null
)
