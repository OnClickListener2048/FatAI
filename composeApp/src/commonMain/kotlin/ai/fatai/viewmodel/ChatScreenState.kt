package ai.fatai.viewmodel

import ai.fatai.chat.ProviderConfig
import ai.fatai.chat.ProviderType
import ai.fatai.feature.files.FileAsset
import ai.fatai.feature.workspace.INBOX_WORKSPACE_ID
import ai.fatai.feature.workspace.Workspace
import ai.fatai.repo.ChatItem
import ai.fatai.repo.Conversation

/** A file upload in flight; [fraction] is 0f..1f of bytes sent. */
data class UploadProgress(
    val id: String,
    val displayName: String,
    val fraction: Float
)

data class ChatScreenState(
    val conversations: List<Conversation> = emptyList(),
    val workspaces: List<Workspace> = emptyList(),
    val currentWorkspaceId: String = INBOX_WORKSPACE_ID,
    val attachments: List<FileAsset> = emptyList(),
    val uploads: List<UploadProgress> = emptyList(),
    val messageAttachments: Map<String, List<FileAsset>> = emptyMap(),
    val messages: List<ChatItem> = emptyList(),
    val currentConversationId: String? = null,
    val chatScrollPosition: ChatScrollPosition = ChatScrollPosition(),
    val isStreaming: Boolean = false,
    val assistantActivity: AssistantActivity? = null,
    val isLoading: Boolean = false,
    val inputText: String = "",
    val activeProvider: ProviderType = ProviderType.OpenAI,
    val activeConfig: ProviderConfig? = null
)

data class ChatScrollPosition(
    val conversationId: String? = null,
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
    val hasSavedPosition: Boolean = false
)

enum class AssistantActivity { Thinking, Searching, CheckingWeather, UsingTool }

/**
 * Prefix for FileAsset ids whose server upload failed; such assets are read through the legacy
 * local-path mode instead of the server-side file store.
 */
internal const val LOCAL_ATTACHMENT_PREFIX = "local-"
