package ai.fatai.sync

/** Feature-neutral boundary for durable server mirroring. */
interface SyncMutationSink {
    fun syncWorkspace(id: String, name: String, systemPrompt: String, isArchived: Boolean = false)
    fun syncConversation(
        id: String,
        workspaceId: String,
        title: String,
        providerType: String,
        model: String,
        isPinned: Boolean = false,
        isArchived: Boolean = false
    )
    fun syncMessage(id: String, conversationId: String, role: String, content: String, contentType: String, reasoningContent: String = "")
    fun syncMemory(id: String, scope: String, content: String, workspaceId: String?, conversationId: String?, kind: String, isArchived: Boolean = false)
    fun syncPrompt(id: String, name: String, content: String, workspaceId: String?, priority: Long, isEnabled: Boolean)
    fun deletePrompt(id: String)
    fun deleteConversation(id: String)
    fun deleteMessage(id: String)
}
