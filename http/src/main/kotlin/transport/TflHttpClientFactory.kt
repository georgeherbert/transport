package transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.time.Duration

fun createTflHttpClient(requestTimeout: Duration) =
    HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            val timeoutMillis = requestTimeout.toMillis()
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = timeoutMillis
            socketTimeoutMillis = timeoutMillis
        }
    }
