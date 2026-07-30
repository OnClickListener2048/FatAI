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
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ai.fatai.tools.ToolRoutes")

fun Application.configureToolRoutes(
    searchService: WebSearchService,
    weatherService: WeatherService,
    doclingDocumentService: DoclingDocumentService
) {
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

        post("/v1/tools/document-read") {
            val request = try {
                json.decodeFromString<DoclingDocumentReadRequest>(call.receiveText())
            } catch (_: Exception) {
                logger.warn("Document read rejected: invalid JSON request")
                call.respondJson(
                    json,
                    ApiError("INVALID_REQUEST", "Request body must be valid JSON."),
                    HttpStatusCode.BadRequest
                )
                return@post
            }

            try {
                val result = doclingDocumentService.read(request)
                logger.info(
                    "Document read returned to client: file={}, markdownChars={}",
                    result.displayName,
                    result.markdown.length
                )
                call.respondJson(json, result)
            } catch (exception: IllegalArgumentException) {
                logger.warn("Document read rejected: file={}, message={}", request.displayName, exception.message)
                call.respondJson(
                    json,
                    ApiError("INVALID_REQUEST", exception.message.orEmpty()),
                    HttpStatusCode.BadRequest
                )
            } catch (exception: DoclingDocumentException) {
                logger.warn(
                    "Document read failed: file={}, code={}, message={}",
                    request.displayName,
                    exception.code,
                    exception.message
                )
                call.respondJson(
                    json,
                    ApiError(exception.code, exception.message.orEmpty()),
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
