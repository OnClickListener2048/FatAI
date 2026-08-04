package ai.fatai.repo

import ai.fatai.database.sqldelight.WatsonQueries
import ai.fatai.bean.ChatItemType
import ai.fatai.bean.MessageContentType
import ai.fatai.chat.markdown.MarkdownDocument
import ai.fatai.chat.markdown.MarkdownDocumentCodec
import ai.fatai.chat.markdown.MarkdownParser
import ai.fatai.feature.user.CurrentUserProvider
import ai.fatai.sync.SyncMutationSink
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
    val markdownDocument: MarkdownDocument? = null,
    val reasoningContent: String = "",
    val isLoading: Boolean = false
)

class ChatRepository(
    private val queries: WatsonQueries,
    private val currentUser: CurrentUserProvider,
    private val serverSync: SyncMutationSink? = null
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
        serverSync?.syncConversation(id, workspaceId, title, providerType.name, model, false, false)
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
        syncConversation(id)
    }

    fun toggleConversationPin(id: String, isPinned: Boolean) {
        queries.updateConversationPin(id = id, isPinned = if (isPinned) 1L else 0L, updatedAt = now(), userId = currentUser.currentUserId)
        syncConversation(id)
    }

    fun toggleConversationArchive(id: String, isArchived: Boolean) {
        queries.updateConversationArchive(id = id, isArchived = if (isArchived) 1L else 0L, updatedAt = now(), userId = currentUser.currentUserId)
        syncConversation(id)
    }

    fun deleteConversation(id: String) {
        val messageIds = queries.selectAllOrderedByTime(id, currentUser.currentUserId).executeAsList().map { it.id }
        queries.deleteByConversationId(id, currentUser.currentUserId)
        queries.deleteConversation(id, currentUser.currentUserId)
        serverSync?.let { sink ->
            // Delete the messages first: outbox coalescing replaces their pending upserts with
            // the new DELETEs, so other devices never re-insert orphans after the conversation
            // delete lands.
            messageIds.forEach(sink::deleteMessage)
            sink.deleteConversation(id)
        }
    }

    fun updateConversationTimestamp(id: String) {
        queries.updateConversationUpdatedAt(id = id, updatedAt = now(), userId = currentUser.currentUserId)
    }

    fun getMessages(conversationId: String): List<ChatItem> {
        return queries.selectAllOrderedByTime(conversationId, currentUser.currentUserId).executeAsList().map { row ->
            val markdownDocument = row.markdownDocument
                .takeIf(String::isNotBlank)
                ?.let(MarkdownDocumentCodec::decode)
                ?: row.content
                    .takeIf { row.contentType == MessageContentType.Markdown && it.isNotBlank() }
                    ?.let(MarkdownParser::parse)
                    ?.also { document ->
                        // Existing conversations receive the persistent render model on first load.
                        queries.updateMarkdownDocumentById(
                            markdownDocument = MarkdownDocumentCodec.encode(document),
                            id = row.id,
                            userId = currentUser.currentUserId
                        )
                    }
            ChatItem(
                id = row.id,
                userId = row.userId,
                conversationId = row.conversationId,
                content = row.content,
                type = row.type,
                contentType = row.contentType,
                createdAt = row.createdAt,
                markdownDocument = markdownDocument
            )
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun insertMessage(
        conversationId: String,
        content: String,
        type: ChatItemType,
        contentType: MessageContentType = MessageContentType.Markdown,
        id: String = Uuid.random().toString(),
        sync: Boolean = true
    ): ChatItem {
        val time = now()
        val markdownDocument = content
            .takeIf { contentType == MessageContentType.Markdown && it.isNotBlank() }
            ?.let(MarkdownParser::parse)
        queries.insertItem(
            id = id,
            userId = currentUser.currentUserId,
            conversationId = conversationId,
            content = content,
            markdownDocument = markdownDocument?.let(MarkdownDocumentCodec::encode).orEmpty(),
            type = type,
            contentType = contentType,
            createdAt = time
        )
        if (sync) {
            serverSync?.syncMessage(
                id = id,
                conversationId = conversationId,
                role = if (type == ChatItemType.Question) "user" else "assistant",
                content = content,
                contentType = contentType.name
            )
        }
        updateConversationTimestamp(conversationId)
        return ChatItem(
            id = id,
            userId = currentUser.currentUserId,
            conversationId = conversationId,
            content = content,
            type = type,
            contentType = contentType,
            createdAt = time,
            markdownDocument = markdownDocument
        )
    }

    fun updateMessageContent(id: String, content: String) {
        queries.updateContentById(content = content, id = id, userId = currentUser.currentUserId)
        queries.selectById(id, currentUser.currentUserId).executeAsOneOrNull()?.let { row ->
            serverSync?.syncMessage(
                id = row.id,
                conversationId = row.conversationId,
                role = if (row.type == ChatItemType.Question) "user" else "assistant",
                content = content,
                contentType = row.contentType.name
            )
        }
    }

    fun deleteMessage(id: String) {
        val row = queries.selectById(id, currentUser.currentUserId).executeAsOneOrNull()
        queries.deleteById(id, currentUser.currentUserId)
        if (row != null) serverSync?.deleteMessage(id)
    }

    fun getMessageCount(conversationId: String): Int {
        return queries.selectAllOrderedByTime(conversationId, currentUser.currentUserId).executeAsList().size
    }

    private fun syncConversation(id: String) {
        queries.selectConversationById(id, currentUser.currentUserId).executeAsOneOrNull()?.let {
            serverSync?.syncConversation(
                it.id, it.workspaceId, it.title, it.providerType.name, it.model,
                it.isPinned != 0L, it.isArchived != 0L
            )
        }
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
