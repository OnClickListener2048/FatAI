package ai.fatai.feature.tools

import ai.fatai.chat.ProviderType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A platform-neutral tool contract. Implementations can be local, HTTP-backed, or MCP-backed.
 * The registry is deliberately unaware of provider-specific function-calling formats.
 */
interface Tool {
    val definition: ToolDefinition

    suspend fun execute(arguments: Map<String, String>): ToolResult
}

data class ToolDefinition(
    val name: String,
    val displayName: String,
    val description: String,
    val parameters: List<ToolParameter>
)

data class ToolParameter(
    val name: String,
    val description: String,
    val required: Boolean = false,
    val allowedValues: Set<String> = emptySet()
)

data class ToolCall(
    val toolName: String,
    val arguments: Map<String, String> = emptyMap()
)

sealed interface ToolResult {
    data class Success(val content: String) : ToolResult

    data class Failure(
        val code: String,
        val message: String
    ) : ToolResult
}

data class ToolExecution(
    val call: ToolCall,
    val result: ToolResult
)

/** Limits execution to explicitly approved tools and keeps results bounded for prompt/UI use. */
data class ToolExecutionPolicy(
    val allowedToolNames: Set<String>? = null,
    val maxOutputCharacters: Int = 8_000
)

class ToolRegistry(
    tools: List<Tool>,
    private val policy: ToolExecutionPolicy = ToolExecutionPolicy()
) {
    private val toolsByName = tools.associateBy { it.definition.name }

    init {
        require(toolsByName.size == tools.size) { "Tool names must be unique." }
        require(tools.all { TOOL_NAME.matches(it.definition.name) }) {
            "Tool names must contain only lowercase letters, digits, underscores, or hyphens."
        }
        require(policy.maxOutputCharacters > 0) { "maxOutputCharacters must be positive." }
    }

    fun definitions(): List<ToolDefinition> = toolsByName.values
        .map(Tool::definition)
        .sortedBy(ToolDefinition::name)

    suspend fun execute(call: ToolCall): ToolExecution {
        val tool = toolsByName[call.toolName]
            ?: return ToolExecution(call, ToolResult.Failure("TOOL_NOT_FOUND", "Unknown tool: ${call.toolName}"))

        if (policy.allowedToolNames != null && call.toolName !in policy.allowedToolNames) {
            return ToolExecution(call, ToolResult.Failure("TOOL_NOT_ALLOWED", "Tool is not enabled: ${call.toolName}"))
        }

        val parametersByName = tool.definition.parameters.associateBy(ToolParameter::name)
        val unknownParameters = call.arguments.keys - parametersByName.keys
        if (unknownParameters.isNotEmpty()) {
            return ToolExecution(
                call,
                ToolResult.Failure("UNKNOWN_ARGUMENT", "Unsupported argument(s): ${unknownParameters.sorted().joinToString()}")
            )
        }

        val missingParameters = tool.definition.parameters
            .filter { it.required && call.arguments[it.name].isNullOrBlank() }
            .map(ToolParameter::name)
        if (missingParameters.isNotEmpty()) {
            return ToolExecution(
                call,
                ToolResult.Failure("MISSING_ARGUMENT", "Missing required argument(s): ${missingParameters.joinToString()}")
            )
        }

        val invalidParameter = tool.definition.parameters.firstOrNull { parameter ->
            val value = call.arguments[parameter.name]
            value != null && parameter.allowedValues.isNotEmpty() && value !in parameter.allowedValues
        }
        if (invalidParameter != null) {
            return ToolExecution(
                call,
                ToolResult.Failure(
                    "INVALID_ARGUMENT",
                    "${invalidParameter.name} must be one of: ${invalidParameter.allowedValues.sorted().joinToString()}"
                )
            )
        }

        val result = try {
            tool.execute(call.arguments)
        } catch (exception: ToolExecutionException) {
            ToolResult.Failure(exception.code, exception.message ?: "Tool execution failed.")
        } catch (_: Exception) {
            ToolResult.Failure("TOOL_EXECUTION_FAILED", "Tool execution failed.")
        }
        return ToolExecution(call, result.truncateTo(policy.maxOutputCharacters))
    }

    private fun ToolResult.truncateTo(maxLength: Int): ToolResult = when (this) {
        is ToolResult.Success -> if (content.length <= maxLength) this else copy(
            content = content.take(maxLength) + "\n[Output truncated]"
        )
        is ToolResult.Failure -> this
    }

    private companion object {
        val TOOL_NAME = Regex("[a-z][a-z0-9_-]*")
    }
}

class ToolExecutionException(
    val code: String,
    override val message: String
) : IllegalArgumentException(message)

/**
 * Translates provider-neutral tool definitions into one provider's wire schema.
 * This boundary intentionally contains no HTTP code, keeping provider API clients independent
 * from built-in or externally registered tools.
 */
interface ToolProviderAdapter {
    val providers: Set<ProviderType>

    fun encode(definitions: List<ToolDefinition>): ProviderToolPayload

    fun decode(call: ProviderToolCall): ToolCall
}

data class ProviderToolPayload(
    val fieldName: String,
    val value: JsonElement
)

@Serializable
data class ProviderToolCall(
    val id: String? = null,
    val name: String,
    val arguments: Map<String, String> = emptyMap()
)

class ToolProviderAdapterRegistry(adapters: List<ToolProviderAdapter>) {
    private val adaptersByProvider = adapters
        .flatMap { adapter -> adapter.providers.map { provider -> provider to adapter } }
        .toMap()

    init {
        require(adaptersByProvider.size == adapters.sumOf { it.providers.size }) {
            "Only one tool adapter can be registered for a provider."
        }
    }

    fun adapterFor(provider: ProviderType): ToolProviderAdapter? = adaptersByProvider[provider]
}
