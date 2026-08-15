package ai.fatai.feature.model

import ai.fatai.bean.ChatItemType
import ai.fatai.bean.MessageContentType
import ai.fatai.chat.ProviderType
import ai.fatai.database.sqldelight.WatsonQueries
import ai.fatai.feature.user.CurrentUserProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/** Applies server changes directly to the local cache without creating new outbox operations. */
class SyncRemoteStore(
    private val queries: WatsonQueries,
    private val currentUser: CurrentUserProvider
) {
    @OptIn(kotlin.time.ExperimentalTime::class)
    fun apply(change: RemoteSyncChange) {
        val payload = change.payload
        val now = Clock.System.now().toEpochMilliseconds()
        when (change.entityType) {
            "workspace" -> if (change.operation == "DELETE") {
                queries.deleteRemoteWorkspace(change.entityId, currentUser.currentUserId)
            } else {
                queries.upsertRemoteWorkspace(
                    id = change.entityId,
                    userId = currentUser.currentUserId,
                    name = payload.string("name"),
                    systemPrompt = payload.string("system_prompt"),
                    createdAt = now,
                    updatedAt = now,
                    isArchived = payload.bool("is_archived").asLong()
                )
            }
            "conversation" -> if (change.operation == "DELETE") {
                queries.deleteRemoteConversation(change.entityId, currentUser.currentUserId)
            } else {
                queries.upsertRemoteConversation(
                    id = change.entityId,
                    userId = currentUser.currentUserId,
                    title = payload.string("title", "New Chat"),
                    workspaceId = payload.string("workspace_id", "inbox"),
                    providerType = provider(payload.string("provider_type")),
                    model = payload.string("model"),
                    createdAt = now,
                    updatedAt = now,
                    isPinned = payload.bool("is_pinned").asLong(),
                    isArchived = payload.bool("is_archived").asLong(),
                    // Server-authoritative totals; absent on old servers → default 0. REPLACE
                    // semantics mean a stale pull can never accumulate, only converge.
                    totalPromptTokens = payload.long("total_prompt_tokens"),
                    totalCompletionTokens = payload.long("total_completion_tokens")
                )
            }
            "message" -> if (change.operation == "DELETE") {
                queries.deleteRemoteMessage(change.entityId, currentUser.currentUserId)
            } else {
                val messageType = if (payload.string("role") == "user") ChatItemType.Question else ChatItemType.Answer
                // Preserve the local parsed markdown AST and source chips: the insert only
                // fires for new rows, the update refreshes the synced fields.
                queries.upsertRemoteMessage(
                    id = change.entityId,
                    userId = currentUser.currentUserId,
                    conversationId = payload.string("conversation_id", "default"),
                    content = payload.string("content"),
                    markdownDocument = "",
                    type = messageType,
                    contentType = contentType(payload.string("content_type")),
                    createdAt = now
                )
                queries.updateRemoteMessage(
                    id = change.entityId,
                    userId = currentUser.currentUserId,
                    conversationId = payload.string("conversation_id", "default"),
                    content = payload.string("content"),
                    type = messageType,
                    contentType = contentType(payload.string("content_type")),
                    createdAt = now
                )
            }
            "memory" -> if (change.operation == "DELETE") {
                queries.deleteRemoteMemory(change.entityId, currentUser.currentUserId)
            } else {
                queries.upsertRemoteMemory(
                    id = change.entityId,
                    userId = currentUser.currentUserId,
                    scope = payload.string("scope", "GLOBAL"),
                    workspaceId = payload.stringOrNull("workspace_id"),
                    conversationId = payload.stringOrNull("conversation_id"),
                    kind = payload.string("kind", "FACT"),
                    content = payload.string("content"),
                    createdAt = now,
                    updatedAt = now,
                    isArchived = payload.bool("is_archived").asLong()
                )
            }
            "prompt_template" -> if (change.operation == "DELETE") {
                queries.deleteRemotePromptTemplate(change.entityId, currentUser.currentUserId)
            } else {
                queries.upsertRemotePromptTemplate(
                    id = change.entityId,
                    userId = currentUser.currentUserId,
                    name = payload.string("name"),
                    content = payload.string("content"),
                    workspaceId = payload.stringOrNull("workspace_id"),
                    priority = payload.long("priority", 100),
                    isEnabled = payload.bool("is_enabled", true).asLong(),
                    createdAt = now,
                    updatedAt = now
                )
            }
            "model_configuration" -> if (change.operation == "DELETE") {
                queries.deleteRemoteApiKey(change.entityId, currentUser.currentUserId)
            } else {
                val providerType = provider(payload.string("provider_type"))
                val name = payload.string("name")
                val baseUrl = payload.string("base_url")
                val model = payload.string("model")
                val isActive = payload.bool("is_active", true).asLong()
                if (queries.selectApiKeyById(change.entityId, currentUser.currentUserId).executeAsOneOrNull() == null) {
                    queries.upsertRemoteApiKey(
                        id = change.entityId,
                        userId = currentUser.currentUserId,
                        providerType = providerType,
                        name = name,
                        baseUrl = baseUrl,
                        model = model,
                        isActive = isActive,
                        thinkingEnabled = 0,
                        createdAt = now
                    )
                } else {
                    queries.updateRemoteApiKeyMetadata(
                        providerType = providerType,
                        name = name,
                        baseUrl = baseUrl,
                        model = model,
                        isActive = isActive,
                        thinkingEnabled = 0,
                        id = change.entityId,
                        userId = currentUser.currentUserId
                    )
                }
            }
            // Attachments synced from other devices carry the server URL but no local path;
            // downloads and Coil rendering both go through the file id.
            "file_asset" -> if (change.operation == "DELETE") {
                queries.deleteFileAsset(change.entityId, currentUser.currentUserId)
            } else {
                queries.upsertRemoteFileAsset(
                    id = change.entityId,
                    userId = currentUser.currentUserId,
                    workspaceId = payload.stringOrNull("workspace_id"),
                    conversationId = payload.stringOrNull("conversation_id"),
                    messageId = payload.stringOrNull("message_id"),
                    displayName = payload.string("display_name"),
                    mimeType = payload.string("mime_type"),
                    localPath = "",
                    sizeBytes = payload.long("size_bytes"),
                    url = payload.string("url"),
                    createdAt = now
                )
            }
            "setting" -> if (change.operation == "DELETE") {
                queries.deleteRemoteSetting(currentUser.currentUserId, change.entityId)
            } else {
                queries.upsertAppSetting(
                    userId = currentUser.currentUserId,
                    key = payload.string("key", change.entityId),
                    value = payload.string("value"),
                    updatedAt = now
                )
            }
        }
        queries.upsertSyncSequence(
            userId = currentUser.currentUserId,
            entityType = change.entityType,
            entityId = change.entityId,
            sequence = change.sequence
        )
    }
}

data class RemoteSyncChange(
    val cursor: Long,
    val operationId: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val sequence: Long,
    val payload: JsonObject
)

private fun JsonObject.string(key: String, default: String = ""): String =
    this[key]?.jsonPrimitive?.contentOrNull ?: default

private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(key: String, default: Long = 0): Long = string(key).toLongOrNull() ?: default

private fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: default

private fun Boolean.asLong(): Long = if (this) 1L else 0L

private fun provider(value: String): ProviderType =
    runCatching { ProviderType.valueOf(value) }.getOrDefault(ProviderType.Custom)

private fun contentType(value: String): MessageContentType =
    runCatching { MessageContentType.valueOf(value) }.getOrDefault(MessageContentType.Markdown)
