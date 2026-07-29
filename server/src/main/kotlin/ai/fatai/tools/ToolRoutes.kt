package ai.fatai.tools

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.http.ContentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Application.configureToolRoutes(searchService: WebSearchService, weatherService: WeatherService) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    routing {
        get("/health") {
            call.respondJson(json, HealthResponse(status = "ok", service = "fatai-server"))
        }

        post("/v1/tools/search") {
            val request = try {
                json.decodeFromString<WebSearchRequest>(call.receiveText())
            } catch (_: Exception) {
                call.respondJson(
                    json,
                    ApiError("INVALID_REQUEST", "Request body must be valid JSON."),
                    HttpStatusCode.BadRequest
                )
                return@post
            }

            try {
                call.respondJson(json, searchService.search(request))
            } catch (exception: IllegalArgumentException) {
                call.respondJson(
                    json,
                    ApiError("INVALID_REQUEST", exception.message.orEmpty()),
                    HttpStatusCode.BadRequest
                )
            } catch (exception: WebSearchUnavailableException) {
                call.respondJson(
                    json,
                    ApiError("SEARCH_UNAVAILABLE", exception.message.orEmpty()),
                    HttpStatusCode.BadGateway
                )
            }
        }

        post("/v1/tools/weather") {
            val request = try {
                json.decodeFromString<WeatherRequest>(call.receiveText())
            } catch (_: Exception) {
                call.respondJson(
                    json,
                    ApiError("INVALID_REQUEST", "Request body must be valid JSON."),
                    HttpStatusCode.BadRequest
                )
                return@post
            }

            try {
                call.respondJson(json, weatherService.weather(request))
            } catch (exception: IllegalArgumentException) {
                call.respondJson(
                    json,
                    ApiError("INVALID_REQUEST", exception.message.orEmpty()),
                    HttpStatusCode.BadRequest
                )
            } catch (exception: WebSearchUnavailableException) {
                call.respondJson(
                    json,
                    ApiError("WEATHER_UNAVAILABLE", exception.message.orEmpty()),
                    HttpStatusCode.BadGateway
                )
            }
        }
    }
}

private suspend inline fun <reified T> io.ktor.server.application.ApplicationCall.respondJson(
    json: Json,
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK
) {
    respondText(
        text = json.encodeToString(value),
        contentType = ContentType.Application.Json,
        status = status
    )
}
