package ai.fatai.feature.tools

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val DEFAULT_TOOL_SERVER_URL = "http://127.0.0.1:8080"

/** Retrieves current-weather references separately from general web search. */
class WeatherTool(
    private val client: HttpClient,
    private val serverUrl: String = DEFAULT_TOOL_SERVER_URL
) : Tool {
    override val definition = ToolDefinition(
        name = "weather",
        displayName = "Weather",
        description = "Gets current weather and forecasts for a specified location. Use this instead of web search for weather questions; ask for a location when it is missing.",
        parameters = listOf(
            ToolParameter("location", "City, region, or country to get weather for.", true),
            ToolParameter("max_results", "Number of weather sources from 1 to 5; defaults to 3.")
        )
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val location = arguments.getValue("location").trim()
        val maxResults = arguments["max_results"]?.toIntOrNull() ?: 3
        if (location.isBlank()) return ToolResult.Failure("INVALID_ARGUMENT", "location must not be blank.")
        if (maxResults !in 1..5) return ToolResult.Failure("INVALID_ARGUMENT", "max_results must be between 1 and 5.")

        val response = try {
            client.post("${serverUrl.trimEnd('/')}/v1/tools/weather") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(WeatherRequest(location, maxResults)))
            }
        } catch (_: Exception) {
            return ToolResult.Failure("WEATHER_UNAVAILABLE", "The local FatAI weather server is unavailable.")
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return ToolResult.Failure("WEATHER_UNAVAILABLE", "The local FatAI weather server returned ${response.status.value}.")
        }
        val payload = try {
            json.decodeFromString<WeatherResponse>(body)
        } catch (_: Exception) {
            return ToolResult.Failure("WEATHER_UNAVAILABLE", "The local FatAI weather server returned an invalid response.")
        }
        if (payload.results.isEmpty()) return ToolResult.Success("No weather sources were found for: ${payload.location}")
        return ToolResult.Success(
            content = buildString {
                appendLine("Weather references for: ${payload.location}")
                payload.results.forEachIndexed { index, result ->
                    appendLine("${index + 1}. ${result.title}")
                    appendLine(result.snippet)
                    appendLine("Source: ${result.url}")
                }
            }.trim(),
            sources = payload.results.map { result -> ToolSource(result.title, result.url) }
        )
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable private data class WeatherRequest(val location: String, val maxResults: Int)
@Serializable private data class WeatherResponse(val location: String, val results: List<WeatherResult>)
@Serializable private data class WeatherResult(val title: String, val snippet: String, val url: String, val source: String)
