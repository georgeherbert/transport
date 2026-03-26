package transport

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.get
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull

class RailDataHttpTest {
    @Test
    fun `fetchModeStations filters non station stop points and expands line memberships`() =
        withRailDataHttpTestContext {
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

            val result = railData.fetchModeStations(TransportModeName("tube"))

            expectThat(result).isSuccess().hasSize(2)
            expectThat(result).isSuccess().get { first().stationId }.isEqualTo(StationId("940GZZLUGPK"))
            expectThat(result).isSuccess().get { first().lineId }.isEqualTo(LineId("victoria"))
        }

    @Test
    fun `fetchLineRoutes parses the route sequence payload`() =
        withRailDataHttpTestContext {
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

            val result = railData.fetchLineRoutes(LineId("victoria"))

            expectThat(result).isSuccess().get { lineId }.isEqualTo(LineId("victoria"))
            expectThat(result).isSuccess().get { paths }.hasSize(1)
            expectThat(result).isSuccess().get { paths.first().coordinates.first() }.isEqualTo(GeoCoordinate(51.582965, -0.019885))
        }

    @Test
    fun `fetchPredictions parses the bulk arrivals payload for a mode`() =
        withRailDataHttpTestContext {
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

            val result = railData.fetchPredictions(TransportModeName("tube"))

            expectThat(result).isSuccess().hasSize(1)
            expectThat(result).isSuccess().get { first().vehicleId }.isEqualTo(VehicleId("257"))
            expectThat(result).isSuccess().get { first().currentLocation }.isEqualTo(LocationDescription("Approaching Green Park"))
        }

    @Test
    fun `fetchLineRoutes requests regular service geometry`() =
        withRailDataHttpTestContext {
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

            val result = railData.fetchLineRoutes(LineId("victoria"))

            expectThat(result).isSuccess().get { paths }.hasSize(0)
            expectThat(observedQuery.get()).isNotNull().contains("serviceTypes=Regular")
        }

    @Test
    fun `fetchLineRoutes retries once when TfL rate limits the request`() =
        withRailDataHttpTestContext {
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

            val result = railData.fetchLineRoutes(LineId("victoria"))

            expectThat(result).isSuccess().get { lineId }.isEqualTo(LineId("victoria"))
            expectThat(attempts.get()).isEqualTo(2)
        }

    @Test
    fun `fetchModeStations requests additional pages when TfL indicates more stop points are available`() =
        withRailDataHttpTestContext {
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

            val result = railData.fetchModeStations(TransportModeName("tube"))

            expectThat(result).isSuccess().hasSize(2)
            expectThat(requests.get()).isEqualTo(2)
        }

    @Test
    fun `fetchModeStations returns upstream http failure`() =
        withRailDataHttpTestContext {
            respond("/StopPoint/Mode/tube", 503, """{"message":"down"}""")

            val result = railData.fetchModeStations(TransportModeName("tube"))

            expectThat(result)
                .isFailure()
                .isA<TransportError.UpstreamHttpFailure>()
                .get(TransportError.UpstreamHttpFailure::statusCode)
                .isEqualTo(503)
        }

    @Test
    fun `fetchModeStations returns upstream network failure when the socket cannot be reached`() =
        withRailDataHttpTestContext {
            val unreachableHttpClient = createTflHttpClient(Duration.ofMillis(250))
            try {
                val unreachableRailData = createRailDataHttp(
                    "http://127.0.0.1:1",
                    Duration.ofMillis(250),
                    "test-key",
                    unreachableHttpClient
                )

                val result = unreachableRailData.fetchModeStations(TransportModeName("tube"))

                expectThat(result)
                    .isFailure()
                    .isA<TransportError.UpstreamNetworkFailure>()
            } finally {
                unreachableHttpClient.close()
            }
        }

    @Test
    fun `fetchPredictions requests all arrivals and includes credentials in the upstream query string`() =
        withRailDataHttpTestContext {
            val observedQuery = AtomicReference<String?>(null)
            server.createContext(
                "/Mode/elizabeth-line/Arrivals",
                RecordingJsonHandler(observedQuery, 200, "[]")
            )
            val credentialedHttpClient = createTflHttpClient(Duration.ofSeconds(5))
            try {
                val credentialedRailData = createRailDataHttp(
                    "http://127.0.0.1:${server.address.port}",
                    Duration.ofSeconds(5),
                    "secret/key",
                    credentialedHttpClient
                )

                val result = credentialedRailData.fetchPredictions(TransportModeName("elizabeth-line"))

                expectThat(result).isSuccess().hasSize(0)
                expectThat(observedQuery.get()).isNotNull().contains("app_key=secret/key")
                expectThat(observedQuery.get()).isNotNull().contains("count=-1")
            } finally {
                credentialedHttpClient.close()
            }
        }
}
