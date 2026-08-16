package ai.fatai.chat

import kotlinx.serialization.Serializable

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
    val toolCalls: List<ProviderToolCall> = emptyList(),
    /** Server confirmed the chat turn was persisted; false means the client should enqueue via outbox. */
    val persisted: Boolean = true
)

@Serializable
data class ChatUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)

/**
 * Where a model call may be served from.
 *
 * NONE (default) always goes to the cloud gateway. LOCAL_FIRST prefers the on-device local
 * model engine and falls back to the cloud on failure or unavailability (used by memory
 * extraction, where a local failure must not silently drop memory quality). LOCAL_ONLY never
 * falls back to the cloud — its caller owns the fallback (the title service relies on the
 * server's own title generation when the local path fails, so a cloud fallback here would
 * double-bill the same title).
 */
enum class LocalRouteMode { NONE, LOCAL_FIRST, LOCAL_ONLY }

data class ProviderConfig(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val configurationId: String? = null,
    val configurationName: String? = null,
    val providerType: ProviderType = ProviderType.OpenAI,
    val maxTokens: Int = 4096,
    val temperature: Float = 0.7f,
    val topP: Float = 1.0f,
    val systemPrompt: String? = null,
    val thinkingEnabled: Boolean = false,
    val localRoute: LocalRouteMode = LocalRouteMode.NONE
)
