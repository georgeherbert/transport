package transport

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
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
    private lateinit var httpClient: HttpClient
    private lateinit var tubeData: TubeData

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.start()
        httpClient = createTflHttpClient(Duration.ofSeconds(5))
        tubeData = TubeDataHttp(
            TflHttpClientConfig(
                "http://127.0.0.1:${server.address.port}",
                Duration.ofSeconds(5),
                "test-key"
            ),
            httpClient,
            TflPayloadParserHttp(transportJson())
        )
    }

    @AfterTest
    fun tearDown() {
        httpClient.close()
        server.stop(0)
    }

    @Test
    fun `fetchModeStations filters non station stop points and expands line memberships`() {
        runBlocking {
            respond(
                "/StopPoint/Mode/tube",
                200,
                """
                {
                  "stopPoints":[
                    {
                      "id":"940GZZLUGPK",
                      "naptanId":"940GZZLUGPK",
                      "commonName":"Green Park Underground Station",
                      "lat":51.506947,
                      "lon":-0.142787,
                      "stopType":"NaptanMetroStation",
                      "lines":[
                        {"id":"victoria","name":"Victoria","uri":"/Line/victoria","type":"Line"},
                        {"id":"jubilee","name":"Jubilee","uri":"/Line/jubilee","type":"Line"}
                      ]
                    },
                    {
                      "id":"4900ZZLUGPK1",
                      "naptanId":"4900ZZLUGPK1",
                      "commonName":"Green Park Station",
                      "lat":51.506947,
                      "lon":-0.142787,
                      "stopType":"NaptanMetroEntrance",
                      "lines":[]
                    }
                  ],
                  "pageSize":1000,
                  "total":2,
                  "page":1
                }
                """.trimIndent()
            )

            val result = tubeData.fetchModeStations(TransportModeName("tube"))

            expectThat(result).isSuccess().hasSize(2)
            expectThat(result).isSuccess().get { first().stationId }.isEqualTo(StationId("940GZZLUGPK"))
            expectThat(result).isSuccess().get { first().lineId }.isEqualTo(LineId("victoria"))
        }
    }

    @Test
    fun `fetchLineRoutes parses the route sequence payload`() {
        runBlocking {
            respond(
                "/Line/victoria/Route/Sequence/all",
                200,
                """
                {
                  "lineId":"victoria",
                  "lineName":"Victoria",
                  "lineStrings":[
                    "[[[-0.019885,51.582965],[-0.04115,51.586919]]]"
                  ],
                  "stopPointSequences":[
                    {
                      "direction":"outbound",
                      "stopPoint":[
                        {"id":"940GZZLUWWL","name":"Walthamstow Central Underground Station","lat":51.582965,"lon":-0.019885,"stopType":"NaptanMetroStation"},
                        {"id":"940GZZLUBLR","name":"Blackhorse Road Underground Station","lat":51.586919,"lon":-0.04115,"stopType":"NaptanMetroStation"}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )

            val result = tubeData.fetchLineRoutes(LineId("victoria"))

            expectThat(result).isSuccess().get { lineId }.isEqualTo(LineId("victoria"))
            expectThat(result).isSuccess().get { paths }.hasSize(1)
            expectThat(result).isSuccess().get { paths.first().coordinates.first() }.isEqualTo(GeoCoordinate(51.582965, -0.019885))
        }
    }

    @Test
    fun `fetchPredictions parses the bulk arrivals payload for a mode`() {
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

            val result = tubeData.fetchPredictions(TransportModeName("tube"))

            expectThat(result).isSuccess().hasSize(1)
            expectThat(result).isSuccess().get { first().vehicleId }.isEqualTo(VehicleId("257"))
            expectThat(result).isSuccess().get { first().currentLocation }.isEqualTo(LocationDescription("Approaching Green Park"))
        }
    }

    @Test
    fun `fetchVehiclePredictions parses the vehicle arrivals payload`() {
        runBlocking {
            val observedQuery = AtomicReference<String?>(null)
            server.createContext(
                "/Vehicle/257,258/Arrivals",
                RecordingJsonHandler(
                    observedQuery,
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
                        "timeToStation":75,
                        "currentLocation":"Approaching Green Park",
                        "towards":"Walthamstow Central",
                        "expectedArrival":"2026-03-22T00:50:35Z",
                        "timeToLive":"2026-03-22T00:50:35Z",
                        "modeName":"tube"
                      }
                    ]
                    """.trimIndent()
                )
            )

            val result = tubeData.fetchVehiclePredictions(listOf(VehicleId("257"), VehicleId("258")))

            expectThat(result).isSuccess().hasSize(1)
            expectThat(result).isSuccess().get { first().secondsToNextStop }.isEqualTo(Duration.ofSeconds(75))
            expectThat(observedQuery.get()).isNotNull().contains("app_key=test-key")
        }
    }

    @Test
    fun `fetchVehiclePredictions returns empty without calling upstream for an empty batch`() {
        runBlocking {
            val result = tubeData.fetchVehiclePredictions(emptyList())

            expectThat(result).isSuccess().hasSize(0)
        }
    }

    @Test
    fun `fetchLineRoutes requests regular service geometry`() {
        runBlocking {
            val observedQuery = AtomicReference<String?>(null)
            server.createContext(
                "/Line/victoria/Route/Sequence/all",
                RecordingJsonHandler(
                    observedQuery,
                    200,
                    """
                    {
                      "lineId":"victoria",
                      "lineName":"Victoria",
                      "lineStrings":[],
                      "stopPointSequences":[]
                    }
                    """.trimIndent()
                )
            )

            val result = tubeData.fetchLineRoutes(LineId("victoria"))

            expectThat(result).isSuccess().get { paths }.hasSize(0)
            expectThat(observedQuery.get()).isNotNull().contains("serviceTypes=Regular")
        }
    }

    @Test
    fun `fetchLineRoutes retries once when TfL rate limits the request`() {
        runBlocking {
            val attempts = AtomicInteger(0)
            server.createContext(
                "/Line/victoria/Route/Sequence/all"
            ) { exchange ->
                val attempt = attempts.incrementAndGet()
                val body = if (attempt == 1) {
                    """{"message":"Rate limit is exceeded"}"""
                } else {
                    """
                    {
                      "lineId":"victoria",
                      "lineName":"Victoria",
                      "lineStrings":[
                        "[[[-0.019885,51.582965],[-0.04115,51.586919]]]"
                      ],
                      "stopPointSequences":[
                        {
                          "direction":"outbound",
                          "stopPoint":[
                            {"id":"940GZZLUWWL","name":"Walthamstow Central Underground Station","lat":51.582965,"lon":-0.019885,"stopType":"NaptanMetroStation"},
                            {"id":"940GZZLUBLR","name":"Blackhorse Road Underground Station","lat":51.586919,"lon":-0.04115,"stopType":"NaptanMetroStation"}
                          ]
                        }
                      ]
                    }
                    """.trimIndent()
                }
                val status = if (attempt == 1) 429 else 200
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { output ->
                    output.write(bytes)
                }
            }

            val result = tubeData.fetchLineRoutes(LineId("victoria"))

            expectThat(result).isSuccess().get { lineId }.isEqualTo(LineId("victoria"))
            expectThat(attempts.get()).isEqualTo(2)
        }
    }

    @Test
    fun `fetchModeStations requests additional pages when TfL indicates more stop points are available`() {
        runBlocking {
            val requests = AtomicInteger(0)
            server.createContext("/StopPoint/Mode/tube") { exchange ->
                val requestNumber = requests.incrementAndGet()
                val body = if (requestNumber == 1) {
                    """
                    {
                      "stopPoints":[
                        {
                          "id":"940GZZLUACT",
                          "naptanId":"940GZZLUACT",
                          "commonName":"Acton Town Underground Station",
                          "lat":51.50301,
                          "lon":-0.28042,
                          "stopType":"NaptanMetroStation",
                          "lines":[
                            {"id":"district","name":"District","uri":"/Line/district","type":"Line"}
                          ]
                        }
                      ],
                      "pageSize":1,
                      "total":2,
                      "page":1
                    }
                    """.trimIndent()
                } else {
                    """
                    {
                      "stopPoints":[
                        {
                          "id":"940GZZLUGPK",
                          "naptanId":"940GZZLUGPK",
                          "commonName":"Green Park Underground Station",
                          "lat":51.506947,
                          "lon":-0.142787,
                          "stopType":"NaptanMetroStation",
                          "lines":[
                            {"id":"victoria","name":"Victoria","uri":"/Line/victoria","type":"Line"}
                          ]
                        }
                      ],
                      "pageSize":1,
                      "total":2,
                      "page":2
                    }
                    """.trimIndent()
                }
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { output ->
                    output.write(bytes)
                }
            }

            val result = tubeData.fetchModeStations(TransportModeName("tube"))

            expectThat(result).isSuccess().hasSize(2)
            expectThat(requests.get()).isEqualTo(2)
        }
    }

    @Test
    fun `fetchModeStations returns upstream http failure`() {
        runBlocking {
            respond("/StopPoint/Mode/tube", 503, """{"message":"down"}""")

            val result = tubeData.fetchModeStations(TransportModeName("tube"))

            expectThat(result)
                .isFailure()
                .isA<TransportError.UpstreamHttpFailure>()
                .get(TransportError.UpstreamHttpFailure::statusCode)
                .isEqualTo(503)
        }
    }

    @Test
    fun `fetchModeStations returns upstream network failure when the socket cannot be reached`() {
        runBlocking {
            httpClient.close()
            httpClient = createTflHttpClient(Duration.ofMillis(250))
            tubeData = TubeDataHttp(
                TflHttpClientConfig(
                    "http://127.0.0.1:1",
                    Duration.ofMillis(250),
                    "test-key"
                ),
                httpClient,
                TflPayloadParserHttp(transportJson())
            )

            val result = tubeData.fetchModeStations(TransportModeName("tube"))

            expectThat(result)
                .isFailure()
                .isA<TransportError.UpstreamNetworkFailure>()
        }
    }

    @Test
    fun `fetchPredictions requests all arrivals and includes credentials in the upstream query string`() {
        runBlocking {
            val observedQuery = AtomicReference<String?>(null)
            server.createContext(
                "/Mode/elizabeth-line/Arrivals",
                RecordingJsonHandler(observedQuery, 200, "[]")
            )
            httpClient.close()
            httpClient = createTflHttpClient(Duration.ofSeconds(5))
            tubeData = TubeDataHttp(
                TflHttpClientConfig(
                    "http://127.0.0.1:${server.address.port}",
                    Duration.ofSeconds(5),
                    "secret/key"
                ),
                httpClient,
                TflPayloadParserHttp(transportJson())
            )

            val result = tubeData.fetchPredictions(TransportModeName("elizabeth-line"))

            expectThat(result).isSuccess().hasSize(0)
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
