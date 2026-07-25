package ai.fatai.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

expect fun createHttpClient(): HttpClient

fun provideHttpClient(): HttpClient = createHttpClient().config {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                prettyPrint = true
                isLenient = true
                encodeDefaults = true
            }
        )
    }
    install(io.ktor.client.plugins.logging.Logging) {
        logger = object : io.ktor.client.plugins.logging.Logger {
            override fun log(message: String) {
                println("KtorLog => $message") // 打印到控制台
            }
        }
        level = LogLevel.ALL // 级别可以是 NONE, INFO, HEADERS, BODY, ALL
    }
}
