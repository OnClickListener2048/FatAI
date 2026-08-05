package ai.fatai.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

actual fun createHttpClient(): HttpClient {
    return HttpClient(OkHttp) {
        engine {
            config {
                // OkHttp defaults to a 10s socket read timeout. SSE streams can stay silent
                // for tens of seconds while the server runs tool rounds, so the read timeout
                // must cover the whole answer generation.
                connectTimeout(10, TimeUnit.SECONDS)
                readTimeout(10, TimeUnit.MINUTES)
                writeTimeout(30, TimeUnit.SECONDS)
            }
        }
    }
}
