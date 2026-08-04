package ai.fatai.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

actual fun createHttpClient(): HttpClient {
    return HttpClient(CIO) {
        engine {
            // CIO applies its default 15s request timeout to the whole call unless the SSE
            // client API is used. Manual event-stream handling in the chat gateway would be
            // killed mid-stream, so the total request timeout is disabled here.
            requestTimeout = 0
        }
    }
}
