package ai.fatai.tools

import kotlinx.serialization.Serializable

@Serializable
data class WeatherRequest(
    val location: String,
    val maxResults: Int = 3
)

@Serializable
data class WeatherResponse(
    val location: String,
    val results: List<WebSearchResult>
)

interface WeatherProvider {
    suspend fun weather(location: String, maxResults: Int): List<WebSearchResult>
}

class WeatherService(private val provider: WeatherProvider) {
    suspend fun weather(request: WeatherRequest): WeatherResponse {
        val location = request.location.trim()
        require(location.isNotEmpty()) { "location must not be blank" }
        require(location.length <= MAX_LOCATION_LENGTH) { "location must be at most $MAX_LOCATION_LENGTH characters" }
        require(request.maxResults in 1..MAX_RESULTS) { "maxResults must be between 1 and $MAX_RESULTS" }

        return WeatherResponse(
            location = location,
            results = provider.weather(location, request.maxResults)
        )
    }

    private companion object {
        const val MAX_LOCATION_LENGTH = 256
        const val MAX_RESULTS = 5
    }
}

/** Weather-specific provider that discovers and ranks Timeanddate weather pages. */
class TimeAndDateWeatherProvider internal constructor(
    private val htmlSearchClient: DuckDuckGoHtmlSearchClient
) : WeatherProvider {
    override suspend fun weather(location: String, maxResults: Int): List<WebSearchResult> =
        htmlSearchClient.search("$location weather timeanddate", maxResults = MAX_CANDIDATES)
            .sortedBy { !it.url.contains(TIME_AND_DATE_HOST) }
            .map { result ->
                if (result.url.contains(TIME_AND_DATE_HOST)) result.copy(source = "timeanddate") else result
            }
            .take(maxResults)

    private companion object {
        const val TIME_AND_DATE_HOST = "timeanddate.com"
        const val MAX_CANDIDATES = 10
    }
}
