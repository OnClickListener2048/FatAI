package ai.fatai.feature.model

import ai.fatai.chat.ChatMessage
import ai.fatai.chat.ProviderConfig
import ai.fatai.feature.settings.SettingsRepository
import android.content.Context
import android.os.Build
import com.cactus.needle.NeedleJNI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.TimeSource

private val ARM64_ABI = "arm64-v8a"

/**
 * On-device needle2 engine (ARM64-only JNI, `libneedle_jni.so` + bundled `needle2.cact`).
 *
 * Needle2 is a tool-calling model: it never emits free text, every turn is a JSON object
 * with `function_calls`. To keep the plain-text [LocalModelEngine] contract, the session
 * declares a single `respond(text)` tool and the input (system instruction + user message
 * joined, since needle's system slot only carries environment facts) is sent as the query.
 * A refusal (empty `function_calls`) or a parse failure surfaces as [Result.failure] so the
 * router falls back to the cloud gateway instead of degrading the answer.
 *
 * The engine is session-global and synchronous, so every call runs on [Dispatchers.IO]
 * under a mutex. The model + tools stay loaded until process death (v1 simplification);
 * [NeedleJNI.nativeReset] rewinds the conversation between tasks, keeping the loaded model.
 */
class NeedleLocalModelEngine(
    context: Context,
    private val settings: SettingsRepository,
    private val httpFallback: HttpLocalModelEngine
) : LocalModelEngine {

    private val appContext = context.applicationContext

    @Volatile
    private var ready = false

    @Volatile
    private var initError: String? = null

    /** Guards the once-only native load+init; model loading must not run on the main thread. */
    private val initLock = Any()

    /** Serializes all calls into the session-global engine; also guards [outputBuffer]. */
    private val engineMutex = Mutex()

    /** Reused under [engineMutex]; the C API writes the result JSON here. */
    private val outputBuffer = ByteArray(64 * 1024)

    /** Last completion's engine-reported stats (set by [parseResponse]); used by [benchmark]. */
    @Volatile
    private var completionStats: NeedleCompletion? = null

    private val json = Json { ignoreUnknownKeys = true }

    private val enabled: Boolean
        get() = settings.getValue(SETTING_LOCAL_MODEL_ENABLED) == "true"

    private val engineMode: String
        get() = settings.getValue(SETTING_LOCAL_MODEL_ENGINE) ?: LOCAL_MODEL_ENGINE_EMBEDDED

    override val isAvailable: Boolean
        get() = if (engineMode == LOCAL_MODEL_ENGINE_HTTP) {
            httpFallback.isAvailable
        } else {
            enabled && initError == null && Build.SUPPORTED_ABIS.any { it == ARM64_ABI }
        }

    override suspend fun complete(messages: List<ChatMessage>, config: ProviderConfig): Result<String> {
        if (engineMode == LOCAL_MODEL_ENGINE_HTTP) return httpFallback.complete(messages, config)
        if (!isAvailable) {
            return Result.failure(IllegalStateException("Embedded local model is not available."))
        }
        return withContext(Dispatchers.IO) {
            try {
                ensureReady()
                val input = messages.joinToString("\n\n") { it.content }
                val answer = engineMutex.withLock {
                    NeedleJNI.nativeReset()
                    NeedleJNI.nativeComplete(input, config.maxTokens, outputBuffer)
                    parseResponse(outputBuffer).getOrThrow()
                }
                Result.success(answer)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun benchmark(): Result<LocalSpeedResult> {
        if (engineMode == LOCAL_MODEL_ENGINE_HTTP) return httpFallback.benchmark()
        if (!isAvailable) {
            return Result.failure(IllegalStateException("Embedded local model is not available."))
        }
        return withContext(Dispatchers.IO) {
            try {
                ensureReady()
                val input = LOCAL_BENCHMARK_MESSAGES.joinToString("\n\n") { it.content }
                val started = TimeSource.Monotonic.markNow()
                val text = engineMutex.withLock {
                    NeedleJNI.nativeReset()
                    NeedleJNI.nativeComplete(input, 64, outputBuffer)
                    parseResponse(outputBuffer).getOrThrow()
                }
                val elapsedMs = started.elapsedNow().inWholeMilliseconds.toDouble()
                val decodeTps = completionStats?.decodeTps ?: 0.0
                Result.success(
                    LocalSpeedResult(
                        // Needle reports decode TPS in its JSON; fall back to a rough
                        // wall-clock estimate (~4 chars per token) if absent.
                        tokensPerSecond = if (decodeTps > 0) decodeTps else text.length / 4.0 / (elapsedMs / 1000.0),
                        timeToFirstTokenMs = Double.NaN,
                        totalTimeMs = elapsedMs,
                        totalTokens = text.length / 4,
                        response = text
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Loads the bundled model and starts the session with the single `respond` tool.
     * Idempotent; a failure sets [initError] so later calls short-circuit to the cloud.
     */
    private fun ensureReady() {
        if (ready) return
        synchronized(initLock) {
            if (ready) return
            try {
                val cact = appContext.assets.open(ASSET_MODEL_NAME).use { it.readBytes() }
                val loadRc = NeedleJNI.nativeLoad(cact)
                check(loadRc == 0) { "needle_load failed (rc=$loadRc)" }
                val initRc = NeedleJNI.nativeInit(null, RESPOND_TOOLS_JSON)
                check(initRc == 0) { "needle_init failed (rc=$initRc)" }
                ready = true
            } catch (t: Throwable) {
                initError = t.message ?: "needle engine failed to initialize"
                throw t
            }
        }
    }

    private fun parseResponse(buffer: ByteArray): Result<String> {
        val text = String(buffer, Charsets.UTF_8).substringBefore('\u0000')
        if (text.isBlank()) {
            return Result.failure(IllegalStateException("Local completion returned an empty response."))
        }
        val completion = try {
            json.decodeFromString<NeedleCompletion>(text)
        } catch (e: Exception) {
            // Surface the raw output so a schema mismatch is diagnosable in the settings UI.
            return Result.failure(
                IllegalStateException("Local completion was not valid JSON: ${text.take(200)}", e)
            )
        }
        completionStats = completion
        val respond = completion.functionCalls.firstOrNull { it.name == RESPOND_TOOL_NAME }
        val answer = respond?.arguments?.let { respondText(it) }
        if (completion.success == false || answer.isNullOrBlank()) {
            // Empty function_calls is needle's refusal; the router escalates to the cloud.
            val detail = completion.error?.take(120)
                ?: if (completion.success == false) "success=false" else "empty function_calls / missing respond text"
            return Result.failure(
                IllegalStateException("Local model refused the request ($detail): ${text.take(200)}")
            )
        }
        return Result.success(answer)
    }

    /** Extracts the `text` argument, tolerating both object and JSON-string encodings. */
    private fun respondText(arguments: JsonElement): String? {
        val obj = when (arguments) {
            is JsonObject -> arguments
            is JsonPrimitive -> runCatching { json.decodeFromString<JsonObject>(arguments.content) }.getOrNull()
            else -> null
        } ?: return null
        val text = obj[RESPOND_ARGUMENT_NAME]?.jsonPrimitive?.contentOrNull
        if (!text.isNullOrBlank()) return text
        // Some tool-call formats serialize arguments as a JSON string inside the object.
        val encoded = obj[RESPOND_ARGUMENT_NAME]?.jsonPrimitive?.content
        if (encoded != null) {
            val parsed = runCatching { json.decodeFromString<JsonObject>(encoded) }.getOrNull()
            return parsed?.get(RESPOND_ARGUMENT_NAME)?.jsonPrimitive?.contentOrNull
        }
        return null
    }

    private companion object {
        const val ASSET_MODEL_NAME = "needle2.cact"
        const val RESPOND_TOOL_NAME = "respond"
        const val RESPOND_ARGUMENT_NAME = "text"

        val RESPOND_TOOLS_JSON = """
            [{"name":"$RESPOND_TOOL_NAME","description":"Answer the user's request with plain text. The text argument must contain the complete answer, and nothing else.","parameters":{"type":"object","properties":{"$RESPOND_ARGUMENT_NAME":{"type":"string","description":"The complete plain-text answer to the request."}},"required":["$RESPOND_ARGUMENT_NAME"]}}]
        """.trimIndent()
    }
}

@Serializable
private data class NeedleCompletion(
    val type: String? = null,
    val success: Boolean? = null,
    val error: String? = null,
    @SerialName("function_calls")
    val functionCalls: List<NeedleFunctionCall> = emptyList(),
    val reasoning: String? = null,
    val confidence: Double? = null,
    @SerialName("prefill_tps")
    val prefillTps: Double? = null,
    @SerialName("decode_tps")
    val decodeTps: Double? = null,
    @SerialName("peak_ram_mb")
    val peakRamMb: Double? = null
)

@Serializable
private data class NeedleFunctionCall(
    val name: String? = null,
    val arguments: JsonElement? = null
)
