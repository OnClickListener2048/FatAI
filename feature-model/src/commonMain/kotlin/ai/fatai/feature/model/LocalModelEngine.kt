package ai.fatai.feature.model

import ai.fatai.chat.ChatMessage
import ai.fatai.chat.ProviderConfig

/**
 * On-device small-model inference for lightweight tasks (memory extraction, title generation).
 *
 * Implementations are platform-specific: an embedded runtime on Android (the needle2 engine),
 * and an OpenAI-compatible HTTP engine everywhere (a locally hosted service such as cactus serve,
 * Ollama, or LM Studio). The router gateway consults [isAvailable] before dispatching and only
 * ever uses [complete] — no streaming contract, because these tasks produce a single short reply.
 */
interface LocalModelEngine {
    /** Whether a local call can be attempted right now (enabled, configured, model ready). */
    val isAvailable: Boolean

    /** Runs a single non-streaming completion. Never throws; failures are returned as results. */
    suspend fun complete(messages: List<ChatMessage>, config: ProviderConfig): Result<String>

    /**
     * Micro-benchmark for the settings UI: runs one short completion and reports speed.
     * Embedded engines report exact cactus numbers; the HTTP engine estimates from wall-clock
     * time and text length (~4 chars per token).
     */
    suspend fun benchmark(): Result<LocalSpeedResult>
}

/** Speed numbers of one benchmark completion. */
data class LocalSpeedResult(
    val tokensPerSecond: Double,
    val timeToFirstTokenMs: Double,
    val totalTimeMs: Double,
    val totalTokens: Int,
    val response: String
)

/** Fixed benchmark prompt shared by every engine so measurements are comparable. */
val LOCAL_BENCHMARK_MESSAGES = listOf(
    ChatMessage(role = "system", content = "You are a helpful assistant."),
    ChatMessage(role = "user", content = "请用一句话介绍你自己。")
)

const val SETTING_LOCAL_MODEL_ENABLED = "local_model_enabled"
const val SETTING_LOCAL_MODEL_ENGINE = "local_model_engine"
const val SETTING_LOCAL_MODEL_BASE_URL = "local_model_base_url"
const val SETTING_LOCAL_MODEL_NAME = "local_model_name"

/** Engine mode values stored in [SETTING_LOCAL_MODEL_ENGINE] (Android only; others are always HTTP). */
const val LOCAL_MODEL_ENGINE_EMBEDDED = "embedded"
const val LOCAL_MODEL_ENGINE_HTTP = "http"
