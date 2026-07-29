package ai.fatai.repo

import ai.fatai.database.sqldelight.WatsonQueries
import ai.fatai.chat.ProviderType
import ai.fatai.feature.user.CurrentUserProvider
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
    private val currentUser: CurrentUserProvider
) {
    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun now() = Clock.System.now().toEpochMilliseconds()

    fun getAllKeys(): List<ApiKeyInfo> {
        return queries.selectAllApiKeys(currentUser.currentUserId).executeAsList().map { it.toApiKeyInfo() }
    }

    fun getKeysByProvider(providerType: ProviderType): List<ApiKeyInfo> {
        return queries.selectApiKeysByProvider(currentUser.currentUserId, providerType).executeAsList().map { it.toApiKeyInfo() }
    }

    fun getActiveKey(): ApiKeyInfo? {
        return queries.selectActiveApiKey(currentUser.currentUserId).executeAsOneOrNull()?.toApiKeyInfo()
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
            apiKey = apiKey,
            baseUrl = resolvedBaseUrl,
            model = resolvedModel,
            isActive = if (setActive) 1L else 0L,
            createdAt = time
        )

        return ApiKeyInfo(id, currentUser.currentUserId, providerType, name, apiKey, resolvedBaseUrl, resolvedModel, setActive, time)
    }

    fun setActiveKey(id: String) {
        queries.deactivateAllApiKeys(currentUser.currentUserId)
        queries.updateApiKeyActive(id = id, isActive = 1L, userId = currentUser.currentUserId)
    }

    fun deleteKey(id: String) {
        queries.deleteApiKey(id, currentUser.currentUserId)
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
                    apiKey = key.apiKey,
                    baseUrl = key.baseUrl.ifBlank { key.providerType.defaultBaseUrl },
                    model = normalizeModel(key.providerType, key.model),
                    isActive = if (key.isActive) 1L else 0L,
                    createdAt = key.createdAt
                )
            }
        }
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

private fun normalizeModel(providerType: ProviderType, model: String): String = when {
    model.isBlank() -> providerType.defaultModel
    providerType == ProviderType.DeepSeek && model == LEGACY_DEEPSEEK_MODEL -> providerType.defaultModel
    else -> model
}

private const val LEGACY_DEEPSEEK_MODEL = "deepseek-chat"
