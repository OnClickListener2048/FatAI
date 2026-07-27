package ai.fatai.tools

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

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
 * Keyless development provider backed by DuckDuckGo's HTML results page. The Instant Answer API
 * is not a general web-search API and frequently has no useful result for current-event queries.
 * Swap this implementation for Tavily, Bing, Brave, or SerpAPI in production; route and client
 * contracts stay unchanged.
 */
class DuckDuckGoSearchProvider(private val client: HttpClient) : WebSearchProvider {
    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> {
        val response = client.get(SEARCH_URL) {
            parameter("q", queryForProvider(query))
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9,zh-CN;q=0.8")
        }
        if (!response.status.isSuccess()) {
            throw WebSearchUnavailableException("Search provider returned ${response.status.value}.")
        }

        return parseResults(response.bodyAsText())
            .let { results -> if (isWeatherQuery(query)) results.sortedBy { !it.url.contains(TIME_AND_DATE_HOST) } else results }
            .distinctBy(WebSearchResult::url)
            .take(maxResults)
    }

    /**
     * DuckDuckGo's no-JavaScript endpoint has a stable, small result markup. This deliberately
     * extracts only its title, redirect URL, and snippet instead of treating remote HTML as a
     * general-purpose document.
     */
    private fun parseResults(html: String): List<WebSearchResult> {
        val titleMatches = resultTitleRegex.findAll(html).toList()
        return titleMatches.mapIndexedNotNull { index, titleMatch ->
            val nextResultStart = titleMatches.getOrNull(index + 1)?.range?.first ?: html.length
            val resultTail = html.substring(titleMatch.range.last + 1, nextResultStart)
            val url = destinationUrl(titleMatch.groupValues[1]) ?: return@mapIndexedNotNull null
            val title = plainText(titleMatch.groupValues[2]).ifBlank { return@mapIndexedNotNull null }
            val snippet = resultSnippetRegex.find(resultTail)
                ?.groupValues
                ?.get(1)
                ?.let(::plainText)
                .orEmpty()

            WebSearchResult(
                title = title,
                snippet = snippet,
                url = url,
                source = if (url.contains(TIME_AND_DATE_HOST)) "timeanddate" else SOURCE
            )
        }
    }

    private fun queryForProvider(query: String): String =
        if (isWeatherQuery(query)) "$query timeanddate" else query

    private fun isWeatherQuery(query: String): Boolean = weatherQueryRegex.containsMatchIn(query)

    private fun destinationUrl(rawHref: String): String? {
        val href = htmlDecode(rawHref).let { if (it.startsWith("//")) "https:$it" else it }
        val encodedDestination = href.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.substringBefore('=') == "uddg" }
            ?.substringAfter('=', "")
            .orEmpty()

        return if (encodedDestination.isNotBlank()) {
            runCatching { URLDecoder.decode(encodedDestination, StandardCharsets.UTF_8.toString()) }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
        } else {
            href.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
    }

    private fun plainText(html: String): String =
        htmlDecode(html.replace(tagRegex, " ")).replace(whitespaceRegex, " ").trim()

    private fun htmlDecode(value: String): String = value
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace(numericEntityRegex) { entity ->
            val encoded = entity.groupValues[1]
            val codePoint = encoded.removePrefix("x").removePrefix("X").toIntOrNull(
                if (encoded.startsWith("x", ignoreCase = true)) 16 else 10
            )
            codePoint?.takeIf(Character::isValidCodePoint)
                ?.let { String(Character.toChars(it)) }
                ?: entity.value
        }

    private companion object {
        const val SOURCE = "duckduckgo"
        const val TIME_AND_DATE_HOST = "timeanddate.com"
        const val SEARCH_URL = "https://html.duckduckgo.com/html/"
        const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        val resultTitleRegex = Regex(
            """<a\b(?=[^>]*\bclass\s*=\s*["'][^"']*\bresult__a\b[^"']*["'])[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val resultSnippetRegex = Regex(
            """<a\b(?=[^>]*\bclass\s*=\s*["'][^"']*\bresult__snippet\b[^"']*["'])[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val tagRegex = Regex("""<[^>]+>""")
        val whitespaceRegex = Regex("""\s+""")
        val numericEntityRegex = Regex("""&#(x[0-9a-fA-F]+|\d+);""", RegexOption.IGNORE_CASE)
        val weatherQueryRegex = Regex("""(?i)\b(weather|forecast|temperature|rain|snow)\b|天气|气温|温度|降雨|下雨|下雪|预报""")
    }
}

class WebSearchUnavailableException(message: String) : RuntimeException(message)
