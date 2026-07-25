package ai.fatai.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.logging.LogLevel

expect fun createHttpClient(): HttpClient

fun provideHttpClient(): HttpClient = createHttpClient().config {
    install(io.ktor.client.plugins.logging.Logging) {
        logger = object : io.ktor.client.plugins.logging.Logger {
            override fun log(message: String) {
                println("KtorLog => $message") // 打印到控制台
            }
        }
        level = LogLevel.ALL // 级别可以是 NONE, INFO, HEADERS, BODY, ALL
    }
}
