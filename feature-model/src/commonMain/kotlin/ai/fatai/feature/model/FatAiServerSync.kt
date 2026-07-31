package ai.fatai.feature.model

import ai.fatai.feature.settings.SettingsRepository
import ai.fatai.feature.user.CurrentUserProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Mirrors local cache writes to the authenticated FastAPI source of truth in FIFO order. */
class FatAiServerSync(
    private val client: HttpClient,
    private val settings: SettingsRepository,
    private val currentUser: CurrentUserProvider,
    private val serverUrl: String = DEFAULT_FAT_AI_SERVER_URL
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncMutex = Mutex()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun syncWorkspace(id: String, name: String, systemPrompt: String) = enqueue {
        post("/v1/workspaces", WorkspacePayload(id, name, systemPrompt))
    }

    fun syncConversation(id: String, workspaceId: String, title: String, providerType: String, model: String) = enqueue {
        post("/v1/conversations", ConversationPayload(id, workspaceId, title, providerType, model))
    }

    fun syncMessage(id: String, conversationId: String, role: String, content: String, contentType: String, reasoningContent: String = "") = enqueue {
        post("/v1/conversations/$conversationId/messages", MessagePayload(id, role, content, reasoningContent, contentType))
    }

    fun syncMemory(id: String, scope: String, content: String, workspaceId: String?, conversationId: String?, kind: String) = enqueue {
        post("/v1/memories", MemoryPayload(id, scope, content, workspaceId, conversationId, kind))
    }

    fun syncPrompt(id: String, name: String, content: String, workspaceId: String?, priority: Long, isEnabled: Boolean) = enqueue {
        post("/v1/prompt-templates", PromptPayload(id, name, content, workspaceId, priority, isEnabled))
    }

    private fun enqueue(block: suspend () -> Unit) {
        scope.launch {
            syncMutex.withLock {
                try {
                    block()
                    _lastError.value = null
                } catch (error: Exception) {
                    _lastError.value = error.message ?: "FatAI server synchronization failed."
                }
            }
        }
    }

    private suspend fun post(path: String, body: Any) {
        val token = accessToken()
        val response = client.post("${serverUrl.trimEnd('/')}$path") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody(
                when (body) {
                    is WorkspacePayload -> json.encodeToString(body)
                    is ConversationPayload -> json.encodeToString(body)
                    is MessagePayload -> json.encodeToString(body)
                    is MemoryPayload -> json.encodeToString(body)
                    is PromptPayload -> json.encodeToString(body)
                    else -> error("Unsupported sync payload")
                }
            )
        }
        if (!response.status.isSuccess()) error(response.bodyAsText().ifBlank { "FatAI server returned ${response.status.value}." })
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun accessToken(): String {
        val deviceId = settings.getValue(DEVICE_ID_KEY) ?: Uuid.random().toString().also {
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
private data class WorkspacePayload(val id: String, val name: String, @SerialName("system_prompt") val systemPrompt: String)

@Serializable
private data class ConversationPayload(
    val id: String,
    @SerialName("workspace_id") val workspaceId: String,
    val title: String,
    @SerialName("provider_type") val providerType: String,
    val model: String
)

@Serializable
private data class MessagePayload(
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
    val kind: String
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
