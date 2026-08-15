package ai.fatai.feature.model

import ai.fatai.chat.ChatMessage
import ai.fatai.chat.OpenAICompatibleProvider
import ai.fatai.chat.ProviderConfig
import ai.fatai.feature.settings.SettingsRepository

/**
 * Local engine backed by any OpenAI-compatible HTTP endpoint (cactus serve, Ollama, LM Studio).
 *
 * The endpoint is device-local configuration, so it is stored in the settings key-value store
 * (never synced to the server) rather than in the provider configuration table.
 */
class HttpLocalModelEngine(
    private val provider: OpenAICompatibleProvider,
    private val settings: SettingsRepository
) : LocalModelEngine {

    override val isAvailable: Boolean
        get() = settings.getValue(SETTING_LOCAL_MODEL_ENABLED) == "true" &&
            !settings.getValue(SETTING_LOCAL_MODEL_BASE_URL).isNullOrBlank()

    override suspend fun complete(messages: List<ChatMessage>, config: ProviderConfig): Result<String> {
        val baseUrl = settings.getValue(SETTING_LOCAL_MODEL_BASE_URL).orEmpty()
        if (baseUrl.isBlank()) {
            return Result.failure(IllegalStateException("Local model base URL is not configured."))
        }
        val model = settings.getValue(SETTING_LOCAL_MODEL_NAME)
            .orEmpty()
            .ifBlank { config.model }
        return provider.chatSync(messages, config.copy(baseUrl = baseUrl, model = model))
    }
}
