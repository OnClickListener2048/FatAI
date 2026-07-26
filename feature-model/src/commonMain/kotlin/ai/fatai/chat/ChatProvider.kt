package ai.fatai.chat

import kotlinx.coroutines.flow.Flow
import ai.fatai.feature.tools.ToolDefinition

interface ChatProvider {
    val type: ProviderType

    suspend fun chat(
        messages: List<ChatMessage>,
        config: ProviderConfig,
        tools: List<ToolDefinition> = emptyList()
    ): Flow<ChatStreamChunk>

    suspend fun chatSync(
        messages: List<ChatMessage>,
        config: ProviderConfig,
        tools: List<ToolDefinition> = emptyList()
    ): Result<String>
}
