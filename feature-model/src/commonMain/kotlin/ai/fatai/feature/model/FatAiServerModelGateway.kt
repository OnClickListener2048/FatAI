package ai.fatai.feature.model

import ai.fatai.chat.ChatMessage
import ai.fatai.chat.ChatStreamChunk
import ai.fatai.chat.ProviderConfig
import ai.fatai.feature.tools.ToolDefinition
import ai.fatai.feature.tools.ProviderToolCall
import io.ktor.client.HttpClient
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val DEFAULT_FAT_AI_SERVER_URL = "http://127.0.0.1:8080"

/**
 * Streams model output from the FatAI FastAPI backend.
 *
 * Provider credentials are server-owned; [ProviderConfig.apiKey] is deliberately never sent.
 * Tool definitions remain client-side for the transitional two-pass tool flow. The backend owns
 * external tool endpoints and will become the tool-call executor as the Agent migration lands.
 */
class FatAiServerModelGateway(
    private val client: HttpClient,
    private val serverUrl: String = DEFAULT_FAT_AI_SERVER_URL
) : ModelGateway {
    override suspend fun stream(
        messages: List<ChatMessage>,
        config: ProviderConfig,
        tools: List<ToolDefinition>
    ): Flow<ChatStreamChunk> = flow {
        client.preparePost("${serverUrl.trimEnd('/')}/v1/chat/stream") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    ServerChatStreamRequest(
                        messages = messages,
                        model = config.model.ifBlank { null },
                        temperature = config.temperature,
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
                            toolCalls += ProviderToolCall(payload.id, payload.name, payload.arguments)
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
    val temperature: Float,
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
