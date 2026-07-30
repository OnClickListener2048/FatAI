package ai.fatai.feature.tools

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Built-in tools are offline-first and safe to expose on every supported platform. */
object DefaultTools {
    fun all(httpClient: HttpClient): List<Tool> = listOf(
        CalculatorTool(),
        TextTransformTool(),
        JsonTool(),
        CurrentTimeTool(),
        UuidTool(),
        WebSearchTool(httpClient),
        WeatherTool(httpClient),
        DoclingDocumentTool(httpClient)
    )
}

class CalculatorTool : Tool {
    override val definition = ToolDefinition(
        name = "calculator",
        displayName = "Calculator",
        description = "Evaluates a basic arithmetic expression without executing code.",
        parameters = listOf(ToolParameter("expression", "Expression using +, -, *, /, %, and parentheses.", true))
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val value = ArithmeticExpression(arguments.getValue("expression")).evaluate()
        val rendered = if (value % 1.0 == 0.0 && value in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
        return ToolResult.Success(rendered)
    }
}

class TextTransformTool : Tool {
    override val definition = ToolDefinition(
        name = "text_transform",
        displayName = "Text transform",
        description = "Transforms text or returns basic text statistics.",
        parameters = listOf(
            ToolParameter("text", "Source text.", true),
            ToolParameter(
                "operation",
                "Requested operation.",
                true,
                setOf("uppercase", "lowercase", "trim", "titlecase", "word_count", "character_count", "replace")
            ),
            ToolParameter("find", "Text to replace; required only for replace."),
            ToolParameter("replacement", "Replacement text; defaults to an empty string.")
        )
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val text = arguments.getValue("text")
        val output = when (arguments.getValue("operation")) {
            "uppercase" -> text.uppercase()
            "lowercase" -> text.lowercase()
            "trim" -> text.trim()
            "titlecase" -> text.toTitleCasePreservingWhitespace()
            "word_count" -> text.trim().takeIf(String::isNotEmpty)
                ?.split(Regex("\\s+"))?.size?.toString() ?: "0"
            "character_count" -> text.length.toString()
            "replace" -> {
                val find = arguments["find"] ?: throw ToolExecutionException(
                    "MISSING_ARGUMENT",
                    "find is required when operation is replace."
                )
                text.replace(find, arguments["replacement"].orEmpty())
            }
            else -> error("Validated by ToolRegistry")
        }
        return ToolResult.Success(output)
    }
}

class JsonTool : Tool {
    override val definition = ToolDefinition(
        name = "json",
        displayName = "JSON formatter",
        description = "Validates and formats JSON locally.",
        parameters = listOf(
            ToolParameter("json", "JSON document.", true),
            ToolParameter("operation", "Format or minify the JSON document.", true, setOf("format", "minify"))
        )
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val document = arguments.getValue("json")
        val element = try {
            Json.parseToJsonElement(document)
        } catch (_: Exception) {
            throw ToolExecutionException("INVALID_JSON", "The supplied value is not valid JSON.")
        }
        val formatter = Json { prettyPrint = arguments.getValue("operation") == "format" }
        return ToolResult.Success(formatter.encodeToString(element))
    }
}

class CurrentTimeTool : Tool {
    override val definition = ToolDefinition(
        name = "current_time",
        displayName = "Current time",
        description = "Returns the current UTC time in ISO-8601 format.",
        parameters = emptyList()
    )

    @OptIn(ExperimentalTime::class)
    override suspend fun execute(arguments: Map<String, String>): ToolResult =
        ToolResult.Success(Clock.System.now().toString())
}

class UuidTool : Tool {
    override val definition = ToolDefinition(
        name = "uuid",
        displayName = "UUID generator",
        description = "Generates one to ten random UUIDs locally.",
        parameters = listOf(ToolParameter("count", "Number of UUIDs to generate (1-10)."))
    )

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val count = arguments["count"]?.toIntOrNull() ?: 1
        if (count !in 1..10) throw ToolExecutionException("INVALID_ARGUMENT", "count must be between 1 and 10.")
        return ToolResult.Success((1..count).joinToString("\n") { Uuid.random().toString() })
    }
}

private class ArithmeticExpression(private val source: String) {
    private var index = 0

    fun evaluate(): Double {
        val value = expression()
        skipWhitespace()
        if (index != source.length) invalidExpression()
        if (!value.isFinite()) throw ToolExecutionException("INVALID_RESULT", "The expression produced a non-finite result.")
        return value
    }

    private fun expression(): Double {
        var value = term()
        while (true) {
            skipWhitespace()
            value = when {
                consume('+') -> value + term()
                consume('-') -> value - term()
                else -> return value
            }
        }
    }

    private fun term(): Double {
        var value = factor()
        while (true) {
            skipWhitespace()
            value = when {
                consume('*') -> value * factor()
                consume('/') -> divide(value, factor())
                consume('%') -> divideRemainder(value, factor())
                else -> return value
            }
        }
    }

    private fun factor(): Double {
        skipWhitespace()
        return when {
            consume('+') -> factor()
            consume('-') -> -factor()
            consume('(') -> expression().also {
                skipWhitespace()
                if (!consume(')')) invalidExpression()
            }
            else -> number()
        }
    }

    private fun number(): Double {
        skipWhitespace()
        val start = index
        while (index < source.length && (source[index].isDigit() || source[index] == '.')) index++
        if (start == index) invalidExpression()
        return source.substring(start, index).toDoubleOrNull() ?: invalidExpression()
    }

    private fun divide(left: Double, right: Double): Double {
        if (right == 0.0) throw ToolExecutionException("DIVISION_BY_ZERO", "Division by zero is not allowed.")
        return left / right
    }

    private fun divideRemainder(left: Double, right: Double): Double {
        if (right == 0.0) throw ToolExecutionException("DIVISION_BY_ZERO", "Division by zero is not allowed.")
        return left % right
    }

    private fun consume(expected: Char): Boolean {
        if (index >= source.length || source[index] != expected) return false
        index++
        return true
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) index++
    }

    private fun invalidExpression(): Nothing = throw ToolExecutionException(
        "INVALID_EXPRESSION",
        "Invalid arithmetic expression."
    )
}

private fun String.toTitleCasePreservingWhitespace(): String = buildString(length) {
    var isWordStart = true
    this@toTitleCasePreservingWhitespace.forEach { character ->
        if (character.isWhitespace()) {
            append(character)
            isWordStart = true
        } else {
            append(if (isWordStart) character.uppercaseChar() else character)
            isWordStart = false
        }
    }
}
