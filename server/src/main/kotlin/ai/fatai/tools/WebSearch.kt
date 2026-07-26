package ai.fatai.tools

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class WebSearchRequest(
    val query: String,
    val maxResults: Int = 5
)

@Serializable
data class WebSearchResponse(
    val query: String,
    val results: List<WebSearchResult>
)

@Serializable
data class WebSearchResult(
    val title: String,
    val snippet: String,
    val url: String,
    val source: String
)

@Serializable
data class ApiError(
    val code: String,
    val message: String
)

@Serializable
data class HealthResponse(
    val status: String,
    val service: String
)

interface WebSearchProvider {
    suspend fun search(query: String, maxResults: Int): List<WebSearchResult>
}

class WebSearchService(private val provider: WebSearchProvider) {
    suspend fun search(request: WebSearchRequest): WebSearchResponse {
        val query = request.query.trim()
        require(query.isNotEmpty()) { "query must not be blank" }
        require(query.length <= MAX_QUERY_LENGTH) { "query must be at most $MAX_QUERY_LENGTH characters" }
        require(request.maxResults in 1..MAX_RESULTS) { "maxResults must be between 1 and $MAX_RESULTS" }

        return WebSearchResponse(
            query = query,
            results = provider.search(query, request.maxResults)
        )
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 512
        const val MAX_RESULTS = 10
    }
}

/**
 * Keyless development provider. Swap this implementation for Tavily, Bing, or SerpAPI in
 * production; route and client contracts stay unchanged.
 */
class DuckDuckGoSearchProvider(private val client: HttpClient) : WebSearchProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> {
        val response = client.get("https://api.duckduckgo.com/") {
            parameter("q", query)
            parameter("format", "json")
            parameter("no_html", "1")
            parameter("skip_disambig", "1")
        }
        if (!response.status.isSuccess()) {
            throw WebSearchUnavailableException("Search provider returned ${response.status.value}.")
        }

        val payload = try {
            json.parseToJsonElement(response.bodyAsText()).jsonObject
        } catch (_: Exception) {
            throw WebSearchUnavailableException("Search provider returned an invalid response.")
        }

        return buildList {
            payload.toAbstractResult()?.let(::add)
            payload["RelatedTopics"]?.collectTopicResults(this)
        }.distinctBy(WebSearchResult::url).take(maxResults)
    }

    private fun JsonObject.toAbstractResult(): WebSearchResult? {
        val url = string("AbstractURL") ?: return null
        val title = string("Heading") ?: url
        val snippet = string("AbstractText").orEmpty()
        return WebSearchResult(title, snippet, url, SOURCE)
    }

    private fun JsonElement.collectTopicResults(target: MutableList<WebSearchResult>) {
        when (this) {
            is JsonArray -> forEach { it.collectTopicResults(target) }
            is JsonObject -> {
                val url = string("FirstURL")
                val text = string("Text")
                if (url != null && text != null) {
                    target += WebSearchResult(
                        title = text.substringBefore(" - "),
                        snippet = text,
                        url = url,
                        source = SOURCE
                    )
                }
                this["Topics"]?.collectTopicResults(target)
            }
            else -> Unit
        }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

    private companion object {
        const val SOURCE = "duckduckgo"
    }
}

class WebSearchUnavailableException(message: String) : RuntimeException(message)
