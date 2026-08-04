package ai.fatai.feature.model

import kotlinx.coroutines.flow.Flow
import ai.fatai.chat.ChatMessage
import ai.fatai.chat.ChatProvider
import ai.fatai.chat.ChatStreamChunk
import ai.fatai.chat.ProviderConfig
import ai.fatai.feature.tools.ToolDefinition

/** Server-side context assembly hints. Absent fields keep the request free of context layers. */
data class ChatContext(
    val workspaceId: String? = null,
    val conversationId: String? = null,
    val responseLanguageTag: String? = null,
    /** Formatted, transient tool results (e.g. document reads) appended after history by the server. */
    val toolResults: List<String> = emptyList()
)

/** Model feature boundary. New providers only need to implement this gateway or a ChatProvider adapter. */
interface ModelGateway {
    suspend fun stream(
        messages: List<ChatMessage>,
        config: ProviderConfig,
        tools: List<ToolDefinition> = emptyList(),
        context: ChatContext = ChatContext()
    ): Flow<ChatStreamChunk>
}

class ChatProviderModelGateway(private val provider: ChatProvider) : ModelGateway {
    override suspend fun stream(
        messages: List<ChatMessage>,
        config: ProviderConfig,
        tools: List<ToolDefinition>,
        context: ChatContext
    ): Flow<ChatStreamChunk> = provider.chat(messages, config, tools)
}
