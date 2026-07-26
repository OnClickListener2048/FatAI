package ai.fatai

import ai.fatai.tools.DuckDuckGoSearchProvider
import ai.fatai.tools.WebSearchService
import ai.fatai.tools.configureToolRoutes
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.client.*
import io.ktor.client.engine.cio.*

fun main() {
    embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val httpClient = HttpClient(CIO)
    monitor.subscribe(ApplicationStopped) { httpClient.close() }

    configureToolRoutes(WebSearchService(DuckDuckGoSearchProvider(httpClient)))
    routing {
        get("/") {
            call.respondText("Ktor: ${Greeting().greet()}")
        }
    }
}
