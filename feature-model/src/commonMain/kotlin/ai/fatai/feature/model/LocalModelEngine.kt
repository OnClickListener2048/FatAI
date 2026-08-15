package ai.fatai.feature.model

import ai.fatai.chat.ChatMessage
import ai.fatai.chat.ProviderConfig

/**
 * On-device small-model inference for lightweight tasks (memory extraction, title generation).
 *
 * Implementations are platform-specific: an embedded runtime on Android (cactus-kotlin), and an
 * OpenAI-compatible HTTP engine everywhere (a locally hosted service such as cactus serve,
 * Ollama, or LM Studio). The router gateway consults [isAvailable] before dispatching and only
 * ever uses [complete] — no streaming contract, because these tasks produce a single short reply.
 */
interface LocalModelEngine {
    /** Whether a local call can be attempted right now (enabled, configured, model ready). */
    val isAvailable: Boolean

    /** Runs a single non-streaming completion. Never throws; failures are returned as results. */
    suspend fun complete(messages: List<ChatMessage>, config: ProviderConfig): Result<String>
}

const val SETTING_LOCAL_MODEL_ENABLED = "local_model_enabled"
const val SETTING_LOCAL_MODEL_ENGINE = "local_model_engine"
const val SETTING_LOCAL_MODEL_BASE_URL = "local_model_base_url"
const val SETTING_LOCAL_MODEL_NAME = "local_model_name"

/** Engine mode values stored in [SETTING_LOCAL_MODEL_ENGINE] (Android only; others are always HTTP). */
const val LOCAL_MODEL_ENGINE_EMBEDDED = "embedded"
const val LOCAL_MODEL_ENGINE_HTTP = "http"

/**
 * Optional capability of embedded engines: model file management for the settings UI.
 *
 * Registered only on platforms whose engine downloads model files (Android); the settings screen
 * resolves it with `getOrNull` and hides the download controls when absent.
 */
interface LocalModelDownloader {
    fun isModelDownloaded(model: String): Boolean
    suspend fun downloadModel(model: String): Result<Unit>
}
