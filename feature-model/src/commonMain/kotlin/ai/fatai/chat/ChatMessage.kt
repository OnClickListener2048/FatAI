package ai.fatai.chat

import kotlinx.serialization.Serializable
import ai.fatai.feature.tools.ProviderToolCall

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

data class ChatStreamChunk(
    val content: String,
    val reasoningContent: String = "",
    val isDone: Boolean = false,
    val finishReason: String? = null,
    val usage: ChatUsage? = null,
    val toolCalls: List<ProviderToolCall> = emptyList()
)

@Serializable
data class ChatUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)

data class ProviderConfig(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val providerType: ProviderType = ProviderType.OpenAI,
    val maxTokens: Int = 4096,
    val temperature: Float = 0.7f,
    val topP: Float = 1.0f,
    val systemPrompt: String? = null
)
