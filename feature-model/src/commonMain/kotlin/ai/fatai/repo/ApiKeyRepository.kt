package ai.fatai.repo

import ai.fatai.database.sqldelight.WatsonQueries
import ai.fatai.chat.ProviderType
import ai.fatai.feature.user.CurrentUserProvider
import ai.fatai.feature.model.FatAiServerSync
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class ApiKeyInfo(
    val id: String,
    val userId: String,
    val providerType: ProviderType,
    val name: String,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val isActive: Boolean,
    val createdAt: Long
)

class ApiKeyRepository(
    private val queries: WatsonQueries,
    private val currentUser: CurrentUserProvider,
    private val serverSync: FatAiServerSync
) {
    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun now() = Clock.System.now().toEpochMilliseconds()

    fun getAllKeys(): List<ApiKeyInfo> {
        return queries.selectAllApiKeys(currentUser.currentUserId).executeAsList().map(::removeLocalSecret)
    }

    fun getKeysByProvider(providerType: ProviderType): List<ApiKeyInfo> {
        return queries.selectApiKeysByProvider(currentUser.currentUserId, providerType).executeAsList().map(::removeLocalSecret)
    }

    fun getActiveKey(): ApiKeyInfo? {
        // Reading the active configuration at app startup also migrates every legacy local secret.
        return getAllKeys().firstOrNull { it.isActive }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun addKey(
        providerType: ProviderType,
        name: String,
        apiKey: String,
        baseUrl: String = providerType.defaultBaseUrl,
        model: String = providerType.defaultModel,
        setActive: Boolean = true
    ): ApiKeyInfo {
        val id = Uuid.random().toString()
        val time = now()
        val resolvedBaseUrl = baseUrl.ifBlank { providerType.defaultBaseUrl }
        val resolvedModel = normalizeModel(providerType, model)

        if (setActive) {
            queries.deactivateAllApiKeys(currentUser.currentUserId)
        }

        queries.insertApiKey(
            id = id,
            userId = currentUser.currentUserId,
            providerType = providerType,
            name = name,
            apiKey = "",
            baseUrl = resolvedBaseUrl,
            model = resolvedModel,
            isActive = if (setActive) 1L else 0L,
            createdAt = time
        )

        return ApiKeyInfo(id, currentUser.currentUserId, providerType, name, "", resolvedBaseUrl, resolvedModel, setActive, time).also {
            serverSync.syncModelConfiguration(it.toProviderConfig(apiKey), isActive = setActive)
        }
    }

    fun setActiveKey(id: String) {
        queries.deactivateAllApiKeys(currentUser.currentUserId)
        queries.updateApiKeyActive(id = id, isActive = 1L, userId = currentUser.currentUserId)
        serverSync.activateModelConfiguration(id)
    }

    fun deleteKey(id: String) {
        queries.deleteApiKey(id, currentUser.currentUserId)
        serverSync.deleteModelConfiguration(id)
    }

    fun importKeys(keys: List<ApiKeyInfo>) {
        for (key in keys) {
            val existing = queries.selectApiKeyById(key.id, currentUser.currentUserId).executeAsOneOrNull()
            if (existing == null) {
                queries.insertApiKey(
                    id = key.id,
                    userId = currentUser.currentUserId,
                    providerType = key.providerType,
                    name = key.name,
                    apiKey = "",
                    baseUrl = key.baseUrl.ifBlank { key.providerType.defaultBaseUrl },
                    model = normalizeModel(key.providerType, key.model),
                    isActive = if (key.isActive) 1L else 0L,
                    createdAt = key.createdAt
                )
                if (key.apiKey.isNotBlank()) {
                    serverSync.syncModelConfiguration(key.toProviderConfig(key.apiKey), isActive = key.isActive)
                }
            }
        }
    }

    private fun removeLocalSecret(key: ai.fatai.database.sqldelight.ApiKey): ApiKeyInfo {
        val info = key.toApiKeyInfo()
        if (info.apiKey.isNotBlank()) {
            serverSync.syncModelConfiguration(info.toProviderConfig(info.apiKey), isActive = info.isActive)
            queries.clearApiKeySecret(info.id, currentUser.currentUserId)
        }
        return info.copy(apiKey = "")
    }
}

private fun ai.fatai.database.sqldelight.ApiKey.toApiKeyInfo() = ApiKeyInfo(
    id = id,
    userId = userId,
    providerType = providerType,
    name = name,
    apiKey = apiKey,
    baseUrl = baseUrl,
    model = normalizeModel(providerType, model),
    isActive = isActive != 0L,
    createdAt = createdAt
)

private fun ApiKeyInfo.toProviderConfig(apiKey: String) = ai.fatai.chat.ProviderConfig(
    apiKey = apiKey,
    baseUrl = baseUrl,
    model = model,
    configurationId = id,
    configurationName = name,
    providerType = providerType
)

private fun normalizeModel(providerType: ProviderType, model: String): String = when {
    model.isBlank() -> providerType.defaultModel
    providerType == ProviderType.DeepSeek && model == LEGACY_DEEPSEEK_MODEL -> providerType.defaultModel
    else -> model
}

private const val LEGACY_DEEPSEEK_MODEL = "deepseek-chat"
