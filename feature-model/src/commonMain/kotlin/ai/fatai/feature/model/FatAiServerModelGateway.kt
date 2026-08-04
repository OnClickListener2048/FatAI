package ai.fatai.feature.model

import ai.fatai.chat.ChatMessage
import ai.fatai.chat.ChatStreamChunk
import ai.fatai.chat.ProviderConfig
import ai.fatai.feature.tools.ToolDefinition
import ai.fatai.feature.tools.ProviderToolCall
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val DEFAULT_FAT_AI_SERVER_URL = "http://127.0.0.1:8080"

/**
 * Streams model output from the FatAI FastAPI backend.
 *
 * Provider credentials are server-owned after the one-time configuration upload.
 * Tool definitions remain client-side for the transitional two-pass tool flow. The backend owns
 * external tool endpoints and will become the tool-call executor as the Agent migration lands.
 */
class FatAiServerModelGateway(
    private val client: HttpClient,
    private val serverSync: FatAiServerSync,
    private val serverUrl: String = DEFAULT_FAT_AI_SERVER_URL
) : ModelGateway {
    override suspend fun stream(
        messages: List<ChatMessage>,
        config: ProviderConfig,
        tools: List<ToolDefinition>,
        context: ChatContext
    ): Flow<ChatStreamChunk> = flow {
        serverSync.awaitModelConfiguration(config.configurationId)
        val accessToken = serverSync.accessToken()
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
                        workspaceId = context.workspaceId,
                        conversationId = context.conversationId,
                        responseLanguageTag = context.responseLanguageTag,
                        toolResults = context.toolResults,
                        includeContextualReferences = context.includeContextualReferences,
                        userMessageId = context.userMessageId,
                        assistantMessageId = context.assistantMessageId,
                        tools = tools.map { definition ->
                            ServerToolDefinition(
                                name = definition.name,
                                description = definition.description,
                                parameters = definition.parameters.map { parameter ->
                                    ServerToolParameter(
                                        name = parameter.name,
                                        description = parameter.description,
                                        required = parameter.required,
                                        allowedValues = parameter.allowedValues.sorted()
                                    )
                                }
                            )
                        }
                    )
                )
            )
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw IllegalStateException(response.bodyAsText().ifBlank { "FatAI server returned ${response.status.value}." })
            }

            var eventName: String? = null
            val toolCalls = mutableListOf<ProviderToolCall>()
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                when {
                    line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                    line.startsWith("data:") -> {
                        if (eventName == "message") {
                            val payload = json.decodeFromString<ServerStreamEvent>(line.substringAfter(':').trim())
                            if (payload.content.isNotEmpty()) emit(ChatStreamChunk(content = payload.content))
                        } else if (eventName == "tool_call") {
                            val payload = json.decodeFromString<ServerToolCall>(line.substringAfter(':').trim())
                            val call = ProviderToolCall(payload.id, payload.name, payload.arguments)
                            toolCalls += call
                            // The server executes the tool itself; this chunk only surfaces the
                            // call for progress display and provenance.
                            emit(ChatStreamChunk(content = "", toolCalls = listOf(call)))
                        } else if (eventName == "done") {
                            emit(ChatStreamChunk(content = "", isDone = true, toolCalls = toolCalls.toList()))
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
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("response_language_tag") val responseLanguageTag: String? = null,
    @SerialName("tool_results") val toolResults: List<String> = emptyList(),
    @SerialName("include_contextual_references") val includeContextualReferences: Boolean = true,
    @SerialName("user_message_id") val userMessageId: String? = null,
    @SerialName("assistant_message_id") val assistantMessageId: String? = null,
    val tools: List<ServerToolDefinition> = emptyList()
)

@Serializable
private data class ServerStreamEvent(val content: String = "")

@Serializable
private data class ServerToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ServerToolParameter>
)

@Serializable
private data class ServerToolParameter(
    val name: String,
    val description: String,
    val required: Boolean,
    val allowedValues: List<String>
)

@Serializable
private data class ServerToolCall(
    val id: String? = null,
    val name: String,
    val arguments: Map<String, String> = emptyMap()
)
