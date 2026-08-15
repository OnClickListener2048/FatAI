package ai.fatai.feature.model

import ai.fatai.chat.ChatMessage
import ai.fatai.chat.ProviderConfig
import ai.fatai.feature.settings.SettingsRepository
import android.content.Context
import android.os.Build
import com.cactus.CactusCompletionParams
import com.cactus.CactusContextInitializer
import com.cactus.CactusInitParams
import com.cactus.CactusLM
import com.cactus.CactusModelManager
import com.cactus.ChatMessage as CactusChatMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val ARM64_ABI = "arm64-v8a"

/**
 * Embedded cactus runtime (ARM64-only JNI, `com.cactuscompute:cactus`).
 *
 * Holds the model loaded after the first successful call and keeps it until process death
 * (v1 simplification; unload would free roughly 600MB but adds reload latency on every task).
 * The x86_64 emulator cannot load the JNI library, so [isAvailable] checks the ABI first and
 * the router falls back to the cloud gateway instead of crashing.
 *
 * When the setting `local_model_engine` is "http", the engine delegates to [httpFallback] so an
 * Android device can also target a LAN-hosted OpenAI-compatible service.
 */
class CactusLocalModelEngine(
    context: Context,
    private val settings: SettingsRepository,
    private val httpFallback: HttpLocalModelEngine
) : LocalModelEngine, LocalModelDownloader {

    private val appContext = context.applicationContext

    @Volatile
    private var lm: CactusLM? = null

    @Volatile
    private var initError: String? = null

    private val initMutex = Mutex()

    init {
        try {
            // README documents initializing from Activity.onCreate; applicationContext keeps the
            // engine self-contained. If device testing shows the runtime requires an Activity,
            // this fails gracefully (initError -> cloud fallback) until the call is moved up.
            // Telemetry stays off: no cactus token is set, so nothing is ever uploaded.
            CactusContextInitializer.initialize(appContext)
        } catch (t: Throwable) {
            initError = t.message
        }
    }

    private val enabled: Boolean
        get() = settings.getValue(SETTING_LOCAL_MODEL_ENABLED) == "true"

    private val engineMode: String
        get() = settings.getValue(SETTING_LOCAL_MODEL_ENGINE) ?: LOCAL_MODEL_ENGINE_EMBEDDED

    private val modelName: String
        get() = settings.getValue(SETTING_LOCAL_MODEL_NAME)?.ifBlank { null } ?: "qwen3-0.6"

    override val isAvailable: Boolean
        get() = if (engineMode == LOCAL_MODEL_ENGINE_HTTP) {
            httpFallback.isAvailable
        } else {
            enabled && initError == null && Build.SUPPORTED_ABIS.any { it == ARM64_ABI } &&
                CactusModelManager.isModelDownloaded(modelName)
        }

    override suspend fun complete(messages: List<ChatMessage>, config: ProviderConfig): Result<String> {
        if (engineMode == LOCAL_MODEL_ENGINE_HTTP) return httpFallback.complete(messages, config)
        if (!isAvailable) {
            return Result.failure(IllegalStateException("Embedded local model is not available."))
        }
        return try {
            val model = initModel()
            val result = model.generateCompletion(
                messages = messages.map { CactusChatMessage(content = it.content, role = it.role) },
                params = CactusCompletionParams(
                    maxTokens = config.maxTokens,
                    temperature = config.temperature.toDouble()
                )
            )
            if (result == null || !result.success) {
                Result.failure(IllegalStateException("Local completion failed."))
            } else {
                val response = result.response
                if (response == null || response.isBlank()) {
                    Result.failure(IllegalStateException("Local completion returned an empty response."))
                } else {
                    Result.success(response)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun initModel(): CactusLM = initMutex.withLock {
        lm?.takeIf { it.isLoaded() } ?: CactusLM().also { model ->
            model.initializeModel(CactusInitParams(model = modelName, contextSize = 2048))
            lm = model
        }
    }

    override fun isModelDownloaded(model: String): Boolean = CactusModelManager.isModelDownloaded(model)

    override suspend fun downloadModel(model: String): Result<Unit> = try {
        CactusLM().downloadModel(model)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
