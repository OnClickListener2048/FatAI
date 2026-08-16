package ai.fatai.feature.model

import kotlinx.coroutines.flow.Flow
import ai.fatai.chat.ChatMessage
import ai.fatai.chat.ChatStreamChunk
import ai.fatai.chat.ProviderConfig

/** An attachment referenced by its server-assigned file id; the bytes never leave the server. */
data class ChatDocument(
    val fileId: String,
    val displayName: String,
    val mimeType: String
)

/** Server-side context assembly hints. Absent fields keep the request free of context layers. */
data class ChatContext(
    val workspaceId: String? = null,
    val conversationId: String? = null,
    val responseLanguageTag: String? = null,
    /** Uploaded attachments converted to Markdown by the server before the model stream. */
    val documents: List<ChatDocument> = emptyList(),
    /** Disables templates, workspace instructions and memories for isolated work (attachment analysis). */
    val includeContextualReferences: Boolean = true,
    /** Client-owned id the server persists the user turn under. */
    val userMessageId: String? = null,
    /** Client-owned id the server persists the assistant answer under. */
    val assistantMessageId: String? = null
)

/** Model feature boundary. New providers only need to implement this gateway or a ChatProvider adapter. */
interface ModelGateway {
    suspend fun stream(
        messages: List<ChatMessage>,
        config: ProviderConfig,
        context: ChatContext = ChatContext()
    ): Flow<ChatStreamChunk>
}
