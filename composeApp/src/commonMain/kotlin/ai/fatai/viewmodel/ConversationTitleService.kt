package ai.fatai.viewmodel

import ai.fatai.chat.ChatMessage
import ai.fatai.chat.LocalRouteMode
import ai.fatai.chat.ProviderConfig
import ai.fatai.feature.model.ModelGateway
import ai.fatai.repo.ChatRepository
import kotlinx.coroutines.flow.collect

/**
 * Generates a conversation title on-device after the first turn.
 *
 * The call is routed LOCAL_ONLY: the router never falls back to the cloud, because the server
 * already runs its own title flow while the title is still the default — a cloud attempt here
 * would double-bill the same title. When the local path fails, this service simply does
 * nothing and the server's existing flow takes over.
 */
class ConversationTitleService(
    private val chatRepository: ChatRepository,
    private val modelGateway: ModelGateway
) {
    suspend fun generateIfNeeded(conversationId: String, firstUserMessage: String, config: ProviderConfig) {
        if (firstUserMessage.isBlank()) return
        val conversation = chatRepository.getConversationById(conversationId) ?: return
        if (conversation.title !in DEFAULT_TITLES) return

        val response = StringBuilder()
        try {
            modelGateway.stream(
                messages = listOf(
                    ChatMessage(role = "system", content = TITLE_PROMPT),
                    ChatMessage(role = "user", content = firstUserMessage)
                ),
                config = config.copy(maxTokens = 60, temperature = 0.2f, localRoute = LocalRouteMode.LOCAL_ONLY)
            ).collect { chunk -> response.append(chunk.content) }
        } catch (_: Exception) {
            return
        }

        val title = cleanTitle(response.toString())
        if (title.isEmpty()) return
        chatRepository.updateConversationTitle(conversationId, title)
    }

    private fun cleanTitle(raw: String): String {
        var title = raw.trim().trim('"', '\'', '`', '“', '”', '「', '」')
        title = title.removePrefix("**").removeSuffix("**")
        title = title.trim().trimEnd('.', '!', '?', '。', '！', '？', ':')
        return title.take(MAX_TITLE_LENGTH)
    }

    private companion object {
        val DEFAULT_TITLES = setOf("", "New Chat", "New conversation")
        const val MAX_TITLE_LENGTH = 30

        val TITLE_PROMPT = """
            You create short conversation titles for a chat history. Generate a concise,
            informative title for a conversation that starts with the given user message.
            Write it in the same language as the message, at most 30 characters. Respond with
            ONLY the title text: no quotes, no markdown, no explanation, no trailing punctuation.
        """.trimIndent()
    }
}
