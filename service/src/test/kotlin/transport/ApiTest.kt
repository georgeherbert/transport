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
    private val railLineMapService: RailLineMapService =
        StubRailLineMapService {
            Success(sampleLineMap())
        }
    private val railMapFeedService: RailMapFeedService =
        StubRailMapFeedService(
            {},
            { forceRefresh ->
                Success(sampleMap(true))
            },
            { null },
            emptyFlow()
        )
    private val snapshotService: RailSnapshotService =
        StubRailSnapshotService { forceRefresh ->
            Success(sampleSnapshot(forceRefresh))
        }

    @Test
    fun `root serves the react app shell`() {
        testApplication {
            application {
                transportModule(
                    snapshotService,
                    railLineMapService,
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
    fun `api description is available on api root`() {
        testApplication {
            application {
                transportModule(
                    snapshotService,
                    railLineMapService,
                    railMapFeedService,
                    serviceResponseMapper,
                    transportJson()
                )
            }

            val response = client.get("/api")

            expectThat(response.status).isEqualTo(HttpStatusCode.OK)
            expectThat(response.bodyAsText()).contains("/api/rail/map")
        }
    }

    @Test
    fun `api returns rail snapshot payload`() {
        testApplication {
            application {
                transportModule(
                    snapshotService,
                    railLineMapService,
                    railMapFeedService,
                    serviceResponseMapper,
                    transportJson()
                )
            }

            val response = client.get("/api/rail/live")

            expectThat(response.status).isEqualTo(HttpStatusCode.OK)
            expectThat(response.bodyAsText()).contains("\"trainCount\"")
        }
    }

    @Test
    fun `api maps transport errors to http response`() {
        testApplication {
            application {
                transportModule(
                    StubRailSnapshotService { forceRefresh ->
                        Failure(TransportError.SnapshotUnavailable("TfL unavailable"))
                    },
                    railLineMapService,
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

            val response = client.get("/api/tubes/live")

            expectThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
            expectThat(response.bodyAsText()).contains("snapshot_unavailable")
        }
    }

    @Test
    fun `api returns rail line map payload`() {
        testApplication {
            application {
                transportModule(
                    snapshotService,
                    railLineMapService,
                    railMapFeedService,
                    serviceResponseMapper,
                    transportJson()
                )
            }

            val response = client.get("/api/rail/lines")

            expectThat(response.status).isEqualTo(HttpStatusCode.OK)
            expectThat(response.bodyAsText()).contains("\"paths\"")
        }
    }

    @Test
    fun `api returns cached projected rail map payload`() {
        testApplication {
            application {
                transportModule(
                    snapshotService,
                    railLineMapService,
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
    fun `api keeps tube routes as aliases`() {
        testApplication {
            application {
                transportModule(
                    snapshotService,
                    railLineMapService,
                    railMapFeedService,
                    serviceResponseMapper,
                    transportJson()
                )
            }

            val response = client.get("/api/tubes/map")

            expectThat(response.status).isEqualTo(HttpStatusCode.OK)
            expectThat(response.bodyAsText()).contains("\"coordinate\"")
        }
    }

    @Test
    fun `api streams the current cached rail map snapshot`() {
        testApplication {
            application {
                transportModule(
                    snapshotService,
                    railLineMapService,
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
                    snapshotService,
                    railLineMapService,
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
                    snapshotService,
                    railLineMapService,
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

    private fun sampleSnapshot(forceRefresh: Boolean) =
        LiveRailSnapshot(
            transportSourceName,
            Instant.parse("2026-03-22T00:49:20Z"),
            forceRefresh,
            Duration.ZERO,
            StationQueryCount(1),
            StationFailureCount(0),
            false,
            LiveTrainCount(1),
            listOf(LineId("victoria")),
            listOf(
                LiveRailTrain(
                    TrainId("257"),
                    VehicleId("257"),
                    listOf(LineId("victoria")),
                    listOf(LineName("Victoria")),
                    TrainDirection("outbound"),
                    DestinationName("Walthamstow Central Underground Station"),
                    TowardsDescription("Walthamstow Central"),
                    LocationDescription("Approaching Green Park"),
                    LocationEstimate(
                        LocationType.STATION_BOARD,
                        LocationDescription("Green Park Underground Station"),
                        GeoCoordinate(51.506947, -0.142787),
                        StationReference(
                            StationId("940GZZLUGPK"),
                            StationName("Green Park Underground Station"),
                            GeoCoordinate(51.506947, -0.142787)
                        )
                    ),
                    StationReference(
                        StationId("940GZZLUGPK"),
                        StationName("Green Park Underground Station"),
                        GeoCoordinate(51.506947, -0.142787)
                    ),
                    Duration.ofSeconds(90),
                    Instant.parse("2026-03-22T00:50:50Z"),
                    Instant.parse("2026-03-22T00:49:20Z"),
                    PredictionCount(1)
                )
            )
        )

    private fun sampleLineMap() =
        RailLineMap(
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
            )
        )

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
            sampleLineMap().lines,
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
