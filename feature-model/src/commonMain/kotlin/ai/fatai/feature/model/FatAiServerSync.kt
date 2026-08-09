package ai.fatai.feature.model

import ai.fatai.feature.settings.SettingsRepository
import ai.fatai.feature.user.CurrentUserProvider
import ai.fatai.chat.ProviderConfig
import ai.fatai.sync.SyncMutationSink
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.math.min
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi

/** Mirrors local cache writes to the authenticated FastAPI source of truth in FIFO order. */
@OptIn(kotlin.time.ExperimentalTime::class)
class FatAiServerSync(
    private val client: HttpClient,
    private val settings: SettingsRepository,
    private val currentUser: CurrentUserProvider,
    private val outbox: SyncOutboxStore,
    private val remoteStore: SyncRemoteStore,
    private val serverUrl: String = DEFAULT_FAT_AI_SERVER_URL
) : SyncMutationSink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncMutex = Mutex()
    private val tokenMutex = Mutex()
    private var cachedAccessToken: String? = null
    private val pendingModelUploads = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Emitted (at most once per pull cycle) when remote changes were applied to the local DB. */
    private val _remoteChangesApplied = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val remoteChangesApplied: SharedFlow<Unit> = _remoteChangesApplied.asSharedFlow()

    private val _pendingCount = MutableStateFlow(outbox.pendingCount())
    val pendingCount: StateFlow<Long> = _pendingCount.asStateFlow()

    init {
        scope.launch { pullLoop() }
        scope.launch { drainLoop() }
    }

    override fun syncWorkspace(id: String, name: String, systemPrompt: String, isArchived: Boolean) {
        enqueue("workspace", id, "UPSERT", json.encodeToString(WorkspacePayload(id, name, systemPrompt, isArchived)))
    }

    override fun syncConversation(
        id: String,
        workspaceId: String,
        title: String,
        providerType: String,
        model: String,
        isPinned: Boolean,
        isArchived: Boolean
    ) {
        enqueue(
            "conversation",
            id,
            "UPSERT",
            json.encodeToString(ConversationPayload(id, workspaceId, title, providerType, model, isPinned, isArchived))
        )
    }

    override fun syncMessage(id: String, conversationId: String, role: String, content: String, contentType: String, reasoningContent: String) {
        enqueue(
            "message",
            id,
            "UPSERT",
            json.encodeToString(MessagePayload(conversationId, id, role, content, reasoningContent, contentType))
        )
    }

    override fun syncMemory(
        id: String,
        scope: String,
        content: String,
        workspaceId: String?,
        conversationId: String?,
        kind: String,
        isArchived: Boolean
    ) {
        enqueue(
            "memory",
            id,
            "UPSERT",
            json.encodeToString(MemoryPayload(id, scope, content, workspaceId, conversationId, kind, isArchived))
        )
    }

    override fun syncPrompt(id: String, name: String, content: String, workspaceId: String?, priority: Long, isEnabled: Boolean) {
        enqueue(
            "prompt_template",
            id,
            "UPSERT",
            json.encodeToString(PromptPayload(id, name, content, workspaceId, priority, isEnabled))
        )
    }

    override fun deletePrompt(id: String) = enqueue("prompt_template", id, "DELETE", "{}")

    override fun deleteConversation(id: String) = enqueue("conversation", id, "DELETE", "{}")

    override fun deleteMessage(id: String) = enqueue("message", id, "DELETE", "{}")

    fun syncModelConfiguration(config: ProviderConfig, isActive: Boolean = true) {
        val configurationId = requireNotNull(config.configurationId) { "A local model configuration is required." }
        // Reuse an in-flight upload's deferred so a second save never leaves the first caller
        // awaiting a completion that no longer exists.
        pendingModelUploads.getOrPut(configurationId) { CompletableDeferred() }
        enqueue(
            entityType = "model_configuration",
            entityId = configurationId,
            operation = "UPSERT",
            payload = json.encodeToString(
                ModelConfigurationPayload(
                    id = configurationId,
                    name = config.configurationName ?: config.providerType.displayName,
                    providerType = config.providerType.name,
                    apiKey = config.apiKey,
                    baseUrl = config.baseUrl,
                    model = config.model,
                    isActive = isActive
                )
            )
        )
    }

    fun activateModelConfiguration(id: String) {
        enqueue("model_configuration", id, "UPSERT", json.encodeToString(ModelActivationPayload(id, true)))
    }

    fun deleteModelConfiguration(id: String) {
        enqueue("model_configuration", id, "DELETE", "{}")
    }

    suspend fun awaitModelConfiguration(id: String?) {
        id?.let { pendingModelUploads.remove(it)?.await() }
    }

    private fun enqueue(
        entityType: String,
        entityId: String,
        operation: String,
        payload: String
    ) {
        outbox.enqueue(entityType, entityId, operation, payload)
        _pendingCount.value = outbox.pendingCount()
    }

    private suspend fun drainLoop() {
        while (true) {
            val operation = outbox.pending(Clock.System.now().toEpochMilliseconds(), limit = 1).firstOrNull()
            if (operation == null) {
                delay(1_000)
                continue
            }
            syncMutex.withLock { send(operation) }
        }
    }

    private suspend fun pullLoop() {
        while (true) {
            runCatching { pullRemoteChanges() }
                .onFailure { error ->
                    if ((error as? SyncHttpException)?.status == 401) invalidateAccessToken()
                    _lastError.value = "PULL: " + (error.message ?: "FatAI server pull failed.")
                }
            delay(5_000)
        }
    }

    private suspend fun pullRemoteChanges() {
        var appliedAny = false
        syncMutex.withLock {
            val token = accessToken()
            var cursor = outbox.cursor()
            if (cursor == 0L && outbox.pendingCount() == 0L) {
                val snapshot = client.get("${serverUrl.trimEnd('/')}/v1/sync/snapshot") {
                    header("Authorization", "Bearer $token")
                }.requireSuccess()
                val body = json.decodeFromString<SyncSnapshotResponse>(snapshot.bodyAsText())
                body.entities.forEach { remoteStore.apply(it.toRemoteChange()) }
                outbox.updateCursor(body.cursor)
                cursor = body.cursor
                appliedAny = body.entities.isNotEmpty()
            }
            var hasMore: Boolean
            do {
                val response = client.get("${serverUrl.trimEnd('/')}/v1/sync/changes") {
                    header("Authorization", "Bearer $token")
                    parameter("cursor", cursor)
                    parameter("limit", 100)
                }.requireSuccess()
                val changes = json.decodeFromString<SyncChangesResponse>(response.bodyAsText())
                hasMore = changes.hasMore
                changes.changes.forEach { remoteStore.apply(it.toRemoteChange()) }
                if (changes.nextCursor != cursor) {
                    outbox.updateCursor(changes.nextCursor)
                    cursor = changes.nextCursor
                }
                appliedAny = appliedAny || changes.changes.isNotEmpty()
            } while (hasMore)
            _lastError.value = null
        }
        // Wake the UI (e.g. server-generated conversation titles) without spamming it.
        if (appliedAny) _remoteChangesApplied.tryEmit(Unit)
    }

    private suspend fun send(operation: PendingSyncOperation) {
        outbox.markSending(operation.id)
        try {
            val request = SyncOperationRequest(
                operationId = operation.id,
                entityType = operation.entityType,
                entityId = operation.entityId,
                operation = operation.operation,
                sequence = operation.sequence,
                schemaVersion = operation.schemaVersion,
                payload = json.parseToJsonElement(operation.payload).jsonObject
            )
            val token = accessToken()
            val response = client.post("${serverUrl.trimEnd('/')}/v1/sync/operations") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(json.encodeToString(request))
            }
            if (!response.status.isSuccess()) {
                if (response.status.value == 401) invalidateAccessToken()
                throw SyncHttpException(response.status.value, response.bodyAsText())
            }
            outbox.markSucceeded(operation.id)
            pendingModelUploads.remove(operation.entityId)?.complete(Unit)
            _lastError.value = null
        } catch (error: Exception) {
            val attempt = operation.attemptCount + 1L
            val code = when (error) {
                is SyncHttpException -> "HTTP_${error.status}"
                else -> "NETWORK"
            }
            val message = error.message ?: "FatAI server synchronization failed."
            // 401 is transient: the cached token was rejected and will be re-fetched.
            val permanent = error is SyncHttpException && error.status in 400..499 && error.status !in setOf(401, 408, 409, 429)
            if (permanent) {
                outbox.markFailed(operation, attempt, code, message)
                pendingModelUploads.remove(operation.entityId)?.completeExceptionally(error)
            } else {
                val delayMillis = min(60_000L, 1_000L * (1L shl min(attempt.toInt(), 6)))
                outbox.markRetrying(
                    operation,
                    attempt,
                    Clock.System.now().toEpochMilliseconds() + delayMillis,
                    code,
                    message
                )
            }
            _lastError.value = "$code: $message"
        } finally {
            _pendingCount.value = outbox.pendingCount()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun accessToken(): String {
        cachedAccessToken?.let { return it }
        return tokenMutex.withLock {
            cachedAccessToken?.let { return it }
            requestDeviceToken().also { cachedAccessToken = it }
        }
    }

    private fun invalidateAccessToken() {
        cachedAccessToken = null
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun requestDeviceToken(): String {
        val deviceId = settings.getValue(DEVICE_ID_KEY) ?: stableDeviceId(currentUser.currentUserId).also {
            settings.putValue(DEVICE_ID_KEY, it)
        }
        val response = client.post("${serverUrl.trimEnd('/')}/v1/auth/device") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(DeviceBootstrapPayload(deviceId, "FatAI ${currentUser.currentUserId}")))
        }
        if (!response.status.isSuccess()) error(response.bodyAsText().ifBlank { "Unable to establish FatAI server session." })
        return json.decodeFromString<DeviceToken>(response.bodyAsText()).accessToken
    }

    private companion object {
        const val DEVICE_ID_KEY = "fat_ai_server_device_id"
        val json = Json { ignoreUnknownKeys = true }

        fun stableDeviceId(userId: String): String = "fatai-device-$userId"
    }
}

@Serializable
private data class DeviceBootstrapPayload(
    @SerialName("device_id") val deviceId: String,
    @SerialName("display_name") val displayName: String
)

@Serializable
private data class DeviceToken(@SerialName("access_token") val accessToken: String)

@Serializable
private data class WorkspacePayload(
    val id: String,
    val name: String,
    @SerialName("system_prompt") val systemPrompt: String,
    @SerialName("is_archived") val isArchived: Boolean
)

@Serializable
private data class ConversationPayload(
    val id: String,
    @SerialName("workspace_id") val workspaceId: String,
    val title: String,
    @SerialName("provider_type") val providerType: String,
    val model: String,
    @SerialName("is_pinned") val isPinned: Boolean,
    @SerialName("is_archived") val isArchived: Boolean
)

@Serializable
private data class MessagePayload(
    @SerialName("conversation_id") val conversationId: String,
    val id: String,
    val role: String,
    val content: String,
    @SerialName("reasoning_content") val reasoningContent: String,
    @SerialName("content_type") val contentType: String
)

@Serializable
private data class MemoryPayload(
    val id: String,
    val scope: String,
    val content: String,
    @SerialName("workspace_id") val workspaceId: String?,
    @SerialName("conversation_id") val conversationId: String?,
    val kind: String,
    @SerialName("is_archived") val isArchived: Boolean
)

@Serializable
private data class PromptPayload(
    val id: String,
    val name: String,
    val content: String,
    @SerialName("workspace_id") val workspaceId: String?,
    val priority: Long,
    @SerialName("is_enabled") val isEnabled: Boolean
)

@Serializable
private data class ModelConfigurationPayload(
    val id: String,
    val name: String,
    @SerialName("provider_type") val providerType: String,
    @SerialName("api_key") val apiKey: String,
    @SerialName("base_url") val baseUrl: String,
    val model: String,
    @SerialName("is_active") val isActive: Boolean
)

@Serializable
private data class ModelActivationPayload(
    val id: String,
    @SerialName("is_active") val isActive: Boolean
)

@Serializable
private data class SyncOperationRequest(
    @SerialName("operation_id") val operationId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val operation: String,
    val sequence: Long,
    @SerialName("schema_version") val schemaVersion: Long,
    val payload: JsonObject
)

@Serializable
private data class SyncSnapshotResponse(
    val entities: List<SyncChangeResponse>,
    val cursor: Long
)

@Serializable
private data class SyncChangesResponse(
    val changes: List<SyncChangeResponse>,
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("has_more") val hasMore: Boolean
)

@Serializable
private data class SyncChangeResponse(
    val cursor: Long,
    @SerialName("operation_id") val operationId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val operation: String,
    val sequence: Long,
    val payload: JsonObject
)

private fun SyncChangeResponse.toRemoteChange() = RemoteSyncChange(
    cursor = cursor,
    operationId = operationId,
    entityType = entityType,
    entityId = entityId,
    operation = operation,
    sequence = sequence,
    payload = payload
)

private suspend fun io.ktor.client.statement.HttpResponse.requireSuccess(): io.ktor.client.statement.HttpResponse {
    if (!status.isSuccess()) throw SyncHttpException(status.value, bodyAsText())
    return this
}

private class SyncHttpException(val status: Int, body: String) : RuntimeException(
    body.ifBlank { "FatAI server returned HTTP $status." }
)
