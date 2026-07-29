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

/** Calls FatAI's local server search endpoint and returns compact, citable search results. */
class WebSearchTool(
    private val client: HttpClient,
    private val serverUrl: String = DEFAULT_TOOL_SERVER_URL
) : Tool {
    override val definition = ToolDefinition(
        name = "web_search",
        displayName = "Web search",
        description = "Searches the public web for current information. Use the weather tool, not this tool, for current weather or forecasts.",
        parameters = listOf(
            ToolParameter("query", "Focused web search query.", true),
            ToolParameter("max_results", "Number of results from 1 to 10; defaults to 5.")
        )
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val query = arguments.getValue("query").trim()
        val maxResults = arguments["max_results"]?.toIntOrNull() ?: 5
        if (query.isBlank()) return ToolResult.Failure("INVALID_ARGUMENT", "query must not be blank.")
        if (maxResults !in 1..10) return ToolResult.Failure("INVALID_ARGUMENT", "max_results must be between 1 and 10.")

        val response = try {
            client.post("${serverUrl.trimEnd('/')}/v1/tools/search") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(WebSearchRequest(query, maxResults)))
            }
        } catch (_: Exception) {
            return ToolResult.Failure("SEARCH_UNAVAILABLE", "The local FatAI search server is unavailable.")
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return ToolResult.Failure("SEARCH_UNAVAILABLE", "The local FatAI search server returned ${response.status.value}.")
        }
        val payload = try {
            json.decodeFromString<WebSearchResponse>(body)
        } catch (_: Exception) {
            return ToolResult.Failure("SEARCH_UNAVAILABLE", "The local FatAI search server returned an invalid response.")
        }
        if (payload.results.isEmpty()) return ToolResult.Success("No web results were found for: ${payload.query}")
        return ToolResult.Success(
            content = buildString {
            appendLine("Web results for: ${payload.query}")
            payload.results.forEachIndexed { index, result ->
                appendLine("${index + 1}. ${result.title}")
                appendLine("${result.snippet}")
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

@Serializable private data class WebSearchRequest(val query: String, val maxResults: Int)
@Serializable private data class WebSearchResponse(val query: String, val results: List<WebSearchResult>)
@Serializable private data class WebSearchResult(val title: String, val snippet: String, val url: String, val source: String)
