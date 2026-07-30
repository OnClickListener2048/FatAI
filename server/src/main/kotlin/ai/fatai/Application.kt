package ai.fatai

import ai.fatai.tools.DuckDuckGoSearchProvider
import ai.fatai.tools.DuckDuckGoHtmlSearchClient
import ai.fatai.tools.DoclingDocumentService
import ai.fatai.tools.TimeAndDateWeatherProvider
import ai.fatai.tools.WeatherService
import ai.fatai.tools.WebSearchService
import ai.fatai.tools.configureToolRoutes
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.client.*
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.Url

fun main() {
    embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val httpClient = HttpClient(OkHttp) {
        engine {
            environmentHttpProxy()?.let { proxy = ProxyBuilder.http(it) }
        }
    }
    monitor.subscribe(ApplicationStopped) { httpClient.close() }

    val htmlSearchClient = DuckDuckGoHtmlSearchClient(httpClient)
    configureToolRoutes(
        searchService = WebSearchService(DuckDuckGoSearchProvider(htmlSearchClient)),
        weatherService = WeatherService(TimeAndDateWeatherProvider(htmlSearchClient)),
        doclingDocumentService = DoclingDocumentService(httpClient)
    )
    routing {
        get("/") {
            call.respondText("Ktor: ${Greeting().greet()}")
        }
    }
}

/**
 * CIO does not automatically honor shell proxy variables. Respect the conventional variables so
 * local development servers can reach web providers on networks that require an HTTP proxy.
 */
private fun environmentHttpProxy(): Url? = sequenceOf(
    "HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy"
).mapNotNull(System::getenv)
    .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
    ?.let(::Url)
