package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.isEqualTo

class ApiTest {
    private val serviceResponseMapper: ServiceResponseMapper = ServiceResponseMapperHttp()
    private val railMapFeedService: RailMapFeedService =
        StubRailMapFeedService(
            {},
            { forceRefresh ->
                Success(sampleMap(true))
            },
            { null },
            emptyFlow()
        )

    @Test
    fun `root serves the react app shell`() {
        testApplication {
            application {
                transportModule(
                    railMapFeedService,
                    serviceResponseMapper,
                    transportJson()
                )
            }

            val response = client.get("/")

            expectThat(response.status).isEqualTo(HttpStatusCode.OK)
            expectThat(response.bodyAsText()).contains("London Rail and Tram Pulse")
        }
    }

    @Test
    fun `api only exposes frontend routes`() {
        testApplication {
            application {
                transportModule(
                    railMapFeedService,
                    serviceResponseMapper,
                    transportJson()
                )
            }

            val apiRootResponse = client.get("/api")
            val healthResponse = client.get("/health")
            val liveResponse = client.get("/api/rail/live")
            val linesResponse = client.get("/api/rail/lines")
            val tubeAliasResponse = client.get("/api/tubes/map")

            expectThat(apiRootResponse.status).isEqualTo(HttpStatusCode.NotFound)
            expectThat(healthResponse.status).isEqualTo(HttpStatusCode.NotFound)
            expectThat(liveResponse.status).isEqualTo(HttpStatusCode.NotFound)
            expectThat(linesResponse.status).isEqualTo(HttpStatusCode.NotFound)
            expectThat(tubeAliasResponse.status).isEqualTo(HttpStatusCode.NotFound)
        }
    }

    @Test
    fun `api maps transport errors to http response`() {
        testApplication {
            application {
                transportModule(
                    StubRailMapFeedService(
                        {},
                        { forceRefresh ->
                            Failure(TransportError.SnapshotUnavailable("TfL unavailable"))
                        },
                        { TransportError.SnapshotUnavailable("TfL unavailable") },
                        emptyFlow()
                    ),
                    serviceResponseMapper,
                    transportJson()
                )
            }

            val response = client.get("/api/rail/map")

            expectThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
            expectThat(response.bodyAsText()).contains("snapshot_unavailable")
        }
    }

    @Test
    fun `api returns cached projected rail map payload`() {
        testApplication {
            application {
                transportModule(
                    railMapFeedService,
                    serviceResponseMapper,
                    transportJson()
                )
            }

            val response = client.get("/api/rail/map")
            val body = response.bodyAsText()
            val payload = transportJson().parseToJsonElement(body).jsonObject

            expectThat(response.status).isEqualTo(HttpStatusCode.OK)
            expectThat(payload["cached"]?.jsonPrimitive?.content).isEqualTo("true")
            expectThat(payload["trainCount"]?.jsonPrimitive?.int).isEqualTo(1)
            expectThat(body).contains("\"stations\"")
        }
    }

    @Test
    fun `api streams the current cached rail map snapshot`() {
        testApplication {
            application {
                transportModule(
                    railMapFeedService,
                    serviceResponseMapper,
                    transportJson()
                )
            }

            client.prepareGet("/api/rail/map/stream").execute { response ->
                val channel: ByteReadChannel = response.body()
                val eventLines = withTimeout(1000) {
                    readSseEventLines(channel)
                }
                val payload = transportJson()
                    .parseToJsonElement(joinSseDataLines(eventLines))
                    .jsonObject

                expectThat(response.status).isEqualTo(HttpStatusCode.OK)
                expectThat(eventLines.joinToString("\n")).contains("event: snapshot")
                expectThat(payload["trainCount"]?.jsonPrimitive?.int).isEqualTo(1)
            }
        }
    }

    @Test
    fun `api streams the current upstream error`() {
        testApplication {
            application {
                transportModule(
                    StubRailMapFeedService(
                        {},
                        { forceRefresh ->
                            Failure(TransportError.UpstreamHttpFailure("/Mode/tube/Arrivals", 503, "down"))
                        },
                        { TransportError.UpstreamHttpFailure("/Mode/tube/Arrivals", 503, "down") },
                        emptyFlow()
                    ),
                    serviceResponseMapper,
                    transportJson()
                )
            }

            client.prepareGet("/api/rail/map/stream").execute { response ->
                val channel: ByteReadChannel = response.body()
                val eventLines = withTimeout(1000) {
                    readSseEventLines(channel)
                }

                expectThat(response.status).isEqualTo(HttpStatusCode.OK)
                expectThat(eventLines.joinToString("\n")).contains("event: transport_error")
                expectThat(eventLines.joinToString("\n")).contains("upstream_http_failure")
            }
        }
    }

    @Test
    fun `api streams train-only position updates after the initial snapshot`() {
        testApplication {
            application {
                transportModule(
                    StubRailMapFeedService(
                        {},
                        { forceRefresh ->
                            Success(sampleMap(true))
                        },
                        { null },
                        flowOf(
                            RailMapFeedUpdate.TrainPositionsUpdated(sampleTrainPositions())
                        )
                    ),
                    serviceResponseMapper,
                    transportJson()
                )
            }

            client.prepareGet("/api/rail/map/stream").execute { response ->
                val channel: ByteReadChannel = response.body()
                readSseEventLines(channel)
                val eventLines = withTimeout(1000) {
                    readSseEventLines(channel)
                }
                val payload = transportJson()
                    .parseToJsonElement(joinSseDataLines(eventLines))
                    .jsonObject

                expectThat(response.status).isEqualTo(HttpStatusCode.OK)
                expectThat(eventLines.joinToString("\n")).contains("event: train_positions")
                expectThat(payload["trainCount"]?.jsonPrimitive?.int).isEqualTo(1)
                expectThat(payload.containsKey("lines")).isEqualTo(false)
            }
        }
    }

    private suspend fun readSseEventLines(channel: ByteReadChannel): List<String> {
        val lines = mutableListOf<String>()

        while (true) {
            val line = checkNotNull(channel.readLine()) {
                "Unexpected end of SSE stream."
            }
            if (line.startsWith("retry: ")) {
                continue
            }

            if (line.isBlank() && lines.isNotEmpty()) {
                return lines
            }

            if (line.isNotBlank()) {
                lines.add(line)
            }
        }
    }

    private fun joinSseDataLines(lines: List<String>) =
        lines
            .filter { line -> line.startsWith("data: ") }
            .joinToString("\n") { line -> line.removePrefix("data: ") }

    private fun sampleMap(forceRefresh: Boolean) =
        RailMapSnapshot(
            transportSourceName,
            Instant.parse("2026-03-22T00:49:20Z"),
            forceRefresh,
            Duration.ZERO,
            StationQueryCount(1),
            StationFailureCount(0),
            false,
            LiveTrainCount(1),
            listOf(
                RailLine(
                    LineId("victoria"),
                    LineName("Victoria"),
                    listOf(
                        RailLinePath(
                            listOf(
                                GeoCoordinate(51.496359, -0.143102),
                                GeoCoordinate(51.506947, -0.142787)
                            )
                        )
                    ),
                    emptyList()
                )
            ),
            listOf(
                MapStation(
                    StationId("940GZZLUGPK"),
                    StationName("Green Park Underground Station"),
                    GeoCoordinate(51.506947, -0.142787),
                    listOf(LineId("victoria"))
                )
            ),
            listOf(
                RailMapTrain(
                    TrainId("victoria|257"),
                    VehicleId("257"),
                    LineId("victoria"),
                    LineName("Victoria"),
                    TrainDirection("outbound"),
                    DestinationName("Walthamstow Central Underground Station"),
                    TowardsDescription("Walthamstow Central"),
                    LocationDescription("Approaching Green Park"),
                    StationReference(
                        StationId("940GZZLUGPK"),
                        StationName("Green Park Underground Station"),
                        GeoCoordinate(51.506947, -0.142787)
                    ),
                    GeoCoordinate(51.506947, -0.142787),
                    HeadingDegrees(42.0),
                    Duration.ofSeconds(90),
                    Instant.parse("2026-03-22T00:50:50Z"),
                    Instant.parse("2026-03-22T00:49:20Z")
                )
            )
        )

    private fun sampleTrainPositions() =
        RailMapTrainPositions(
            transportSourceName,
            Instant.parse("2026-03-22T00:49:20Z"),
            true,
            Duration.ofSeconds(20),
            StationQueryCount(1),
            StationFailureCount(0),
            false,
            LiveTrainCount(1),
            sampleMap(true).trains
        )
}
