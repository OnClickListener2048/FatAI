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
        level = LogLevel.INFO // Never log request headers or bodies: they may contain API keys and user content.
    }
}
