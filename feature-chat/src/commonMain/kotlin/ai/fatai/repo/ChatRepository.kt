package ai.fatai.repo

import ai.fatai.database.sqldelight.WatsonQueries
import ai.fatai.bean.ChatItemType
import ai.fatai.bean.MessageContentType
import ai.fatai.feature.user.CurrentUserProvider
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlin.uuid.ExperimentalUuidApi

data class Conversation(
    val id: String,
    val userId: String,
    val title: String,
    val workspaceId: String,
    val providerType: ai.fatai.chat.ProviderType,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isPinned: Boolean,
    val isArchived: Boolean
)

data class ChatItem(
    val id: String,
    val userId: String,
    val conversationId: String,
    val content: String,
    val type: ChatItemType,
    val contentType: MessageContentType,
    val createdAt: Long,
    val reasoningContent: String = "",
    val isLoading: Boolean = false
)

class ChatRepository(
    private val queries: WatsonQueries,
    private val currentUser: CurrentUserProvider
) {
    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun now() = Clock.System.now().toEpochMilliseconds()

    @OptIn(ExperimentalUuidApi::class)
    fun createConversation(
        title: String = "New Chat",
        workspaceId: String,
        providerType: ai.fatai.chat.ProviderType,
        model: String = providerType.defaultModel
    ): Conversation {
        val id = Uuid.random().toString()
        val time = now()
        queries.insertConversation(
            id = id,
            userId = currentUser.currentUserId,
            title = title,
            workspaceId = workspaceId,
            providerType = providerType,
            model = model,
            createdAt = time,
            updatedAt = time,
            isPinned = 0L,
            isArchived = 0L
        )
        return Conversation(id, currentUser.currentUserId, title, workspaceId, providerType, model, time, time, false, false)
    }

    fun getConversations(): List<Conversation> {
        return queries.selectConversations(currentUser.currentUserId).executeAsList().map { it.toConversation() }
    }

    fun getConversations(workspaceId: String): List<Conversation> {
        return queries.selectConversationsForWorkspace(currentUser.currentUserId, workspaceId).executeAsList().map { it.toConversation() }
    }

    fun getArchivedConversations(): List<Conversation> {
        return queries.selectArchivedConversations(currentUser.currentUserId).executeAsList().map { it.toConversation() }
    }

    fun searchConversations(query: String): List<Conversation> {
        return queries.searchConversations(query, currentUser.currentUserId).executeAsList().map { it.toConversation() }
    }

    fun getConversationById(id: String): Conversation? {
        return queries.selectConversationById(id, currentUser.currentUserId).executeAsOneOrNull()?.toConversation()
    }

    fun updateConversationTitle(id: String, title: String) {
        queries.updateConversationTitle(id = id, title = title, updatedAt = now(), userId = currentUser.currentUserId)
    }

    fun toggleConversationPin(id: String, isPinned: Boolean) {
        queries.updateConversationPin(id = id, isPinned = if (isPinned) 1L else 0L, updatedAt = now(), userId = currentUser.currentUserId)
    }

    fun toggleConversationArchive(id: String, isArchived: Boolean) {
        queries.updateConversationArchive(id = id, isArchived = if (isArchived) 1L else 0L, updatedAt = now(), userId = currentUser.currentUserId)
    }

    fun deleteConversation(id: String) {
        queries.deleteByConversationId(id, currentUser.currentUserId)
        queries.deleteConversation(id, currentUser.currentUserId)
    }

    fun updateConversationTimestamp(id: String) {
        queries.updateConversationUpdatedAt(id = id, updatedAt = now(), userId = currentUser.currentUserId)
    }

    fun getMessages(conversationId: String): List<ChatItem> {
        return queries.selectAllOrderedByTime(conversationId, currentUser.currentUserId).executeAsList().map { row ->
            ChatItem(
                id = row.id,
                userId = row.userId,
                conversationId = row.conversationId,
                content = row.content,
                type = row.type,
                contentType = row.contentType,
                createdAt = row.createdAt
            )
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun insertMessage(
        conversationId: String,
        content: String,
        type: ChatItemType,
        contentType: MessageContentType = MessageContentType.Markdown
    ): ChatItem {
        val id = Uuid.random().toString()
        val time = now()
        queries.insertItem(
            id = id,
            userId = currentUser.currentUserId,
            conversationId = conversationId,
            content = content,
            type = type,
            contentType = contentType,
            createdAt = time
        )
        updateConversationTimestamp(conversationId)
        return ChatItem(id, currentUser.currentUserId, conversationId, content, type, contentType, time)
    }

    fun updateMessageContent(id: String, content: String) {
        queries.updateContentById(content = content, id = id, userId = currentUser.currentUserId)
    }

    fun deleteMessage(id: String) {
        queries.deleteById(id, currentUser.currentUserId)
    }

    fun getMessageCount(conversationId: String): Int {
        return queries.selectAllOrderedByTime(conversationId, currentUser.currentUserId).executeAsList().size
    }
}

private fun ai.fatai.database.sqldelight.Conversation.toConversation() = Conversation(
    id = id,
    userId = userId,
    title = title,
    workspaceId = workspaceId,
    providerType = providerType,
    model = model,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isPinned = isPinned != 0L,
    isArchived = isArchived != 0L
)
