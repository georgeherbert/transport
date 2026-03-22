package transport

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.get
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull

class TubeDataHttpTest {
    private lateinit var server: HttpServer
    private lateinit var tubeData: TubeData

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.start()
        tubeData = TubeDataHttp(
            TflHttpClientConfig(
                "http://127.0.0.1:${server.address.port}",
                Duration.ofSeconds(5),
                null,
                null
            ),
            HttpClient.newHttpClient(),
            TflPayloadParserHttp(transportJson())
        )
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `fetchLineStations filters non metro stop points`() {
        runBlocking {
            respond(
                "/Line/victoria/StopPoints",
                200,
                """
                [
                  {"id":"940GZZLUGPK","naptanId":"940GZZLUGPK","commonName":"Green Park Underground Station","lat":51.506947,"lon":-0.142787,"stopType":"NaptanMetroStation"},
                  {"id":"4900ZZLUGPK1","naptanId":"4900ZZLUGPK1","commonName":"Green Park Station","lat":51.506947,"lon":-0.142787,"stopType":"NaptanMetroEntrance"}
                ]
                """.trimIndent()
            )

            val result = tubeData.fetchLineStations(LineId("victoria"))

            expectThat(result).isSuccess().hasSize(1)
            expectThat(result).isSuccess().get { first().stationId }.isEqualTo(StationId("940GZZLUGPK"))
            expectThat(result).isSuccess().get { first().lineId }.isEqualTo(LineId("victoria"))
        }
    }

    @Test
    fun `fetchTubePredictions parses the bulk arrivals payload`() {
        runBlocking {
            respond(
                "/Mode/tube/Arrivals",
                200,
                """
                [
                  {
                    "id":"-303938351",
                    "vehicleId":"257",
                    "naptanId":"940GZZLUGPK",
                    "stationName":"Green Park Underground Station",
                    "lineId":"victoria",
                    "lineName":"Victoria",
                    "platformName":"Northbound - Platform 4",
                    "direction":"outbound",
                    "destinationNaptanId":"940GZZLUWWL",
                    "destinationName":"Walthamstow Central Underground Station",
                    "timestamp":"2026-03-22T00:49:20Z",
                    "timeToStation":120,
                    "currentLocation":"Approaching Green Park",
                    "towards":"Walthamstow Central",
                    "expectedArrival":"2026-03-22T00:51:20Z",
                    "timeToLive":"2026-03-22T00:51:20Z",
                    "modeName":"tube"
                  }
                ]
                """.trimIndent()
            )

            val result = tubeData.fetchTubePredictions()

            expectThat(result).isSuccess().hasSize(1)
            expectThat(result).isSuccess().get { first().vehicleId }.isEqualTo(VehicleId("257"))
            expectThat(result).isSuccess().get { first().currentLocation }.isEqualTo(LocationDescription("Approaching Green Park"))
        }
    }

    @Test
    fun `fetchLineStations returns upstream http failure`() {
        runBlocking {
            respond("/Line/victoria/StopPoints", 503, """{"message":"down"}""")

            val result = tubeData.fetchLineStations(LineId("victoria"))

            expectThat(result)
                .isFailure()
                .isA<TransportError.UpstreamHttpFailure>()
                .get(TransportError.UpstreamHttpFailure::statusCode)
                .isEqualTo(503)
        }
    }

    @Test
    fun `fetchLineStations returns upstream network failure when the socket cannot be reached`() {
        runBlocking {
            tubeData = TubeDataHttp(
                TflHttpClientConfig(
                    "http://127.0.0.1:1",
                    Duration.ofMillis(250),
                    null,
                    null
                ),
                HttpClient.newHttpClient(),
                TflPayloadParserHttp(transportJson())
            )

            val result = tubeData.fetchLineStations(LineId("victoria"))

            expectThat(result)
                .isFailure()
                .isA<TransportError.UpstreamNetworkFailure>()
        }
    }

    @Test
    fun `fetchTubePredictions requests all arrivals and includes credentials in the upstream query string`() {
        runBlocking {
            val observedQuery = AtomicReference<String?>(null)
            server.createContext(
                "/Mode/tube/Arrivals",
                RecordingJsonHandler(observedQuery, 200, "[]")
            )
            tubeData = TubeDataHttp(
                TflHttpClientConfig(
                    "http://127.0.0.1:${server.address.port}",
                    Duration.ofSeconds(5),
                    "my app",
                    "secret/key"
                ),
                HttpClient.newHttpClient(),
                TflPayloadParserHttp(transportJson())
            )

            val result = tubeData.fetchTubePredictions()

            expectThat(result).isSuccess().hasSize(0)
            expectThat(observedQuery.get()).isNotNull().contains("app_id=my+app")
            expectThat(observedQuery.get()).isNotNull().contains("app_key=secret/key")
            expectThat(observedQuery.get()).isNotNull().contains("count=-1")
        }
    }

    private fun respond(path: String, status: Int, body: String) {
        server.createContext(path, StaticJsonHandler(status, body))
    }
}

class StaticJsonHandler(
    private val status: Int,
    private val body: String
) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
    }
}

class RecordingJsonHandler(
    private val observedQuery: AtomicReference<String?>,
    private val status: Int,
    private val body: String
) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        observedQuery.set(exchange.requestURI.query)
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
    }
}
