package transport

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import java.net.InetSocketAddress
import java.time.Duration
import kotlinx.coroutines.runBlocking

class RailDataHttpTestContext(
    val server: HttpServer,
    val railData: RailData
) {
    fun respond(path: String, status: Int, body: String) =
        server.createContext(path, StaticJsonHandler(status, body))
}

fun withRailDataHttpTestContext(block: suspend RailDataHttpTestContext.() -> Unit) {
    runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val httpClient = createTflHttpClient(Duration.ofSeconds(5))
        server.start()
        val testContext = RailDataHttpTestContext(
            server,
            createRailDataHttp(
                "http://127.0.0.1:${server.address.port}",
                Duration.ofSeconds(5),
                "test-key",
                httpClient
            )
        )

        try {
            testContext.block()
        } finally {
            httpClient.close()
            server.stop(0)
        }
    }
}

fun createRailDataHttp(
    baseUrl: String,
    requestTimeout: Duration,
    subscriptionKey: String,
    httpClient: HttpClient
): RailData =
    RailDataHttp(
        TflHttpClientConfig(
            baseUrl,
            requestTimeout,
            subscriptionKey
        ),
        httpClient,
        TflPayloadParserHttp(transportJson())
    )
