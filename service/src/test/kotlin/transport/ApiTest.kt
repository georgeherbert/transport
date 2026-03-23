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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.isEqualTo

class ApiTest {
    private val serviceResponseMapper: ServiceResponseMapper = ServiceResponseMapperHttp()
    private val tubeLineMapService: TubeLineMapService =
        StubTubeLineMapService {
            Success(sampleLineMap())
        }
    private val tubeMapFeedService: TubeMapFeedService =
        StubTubeMapFeedService(
            {},
            { forceRefresh ->
                Success(sampleMap(true))
            },
            { null },
            emptyFlow()
        )
    private val snapshotService: TubeSnapshotService =
        StubTubeSnapshotService { forceRefresh ->
            Success(sampleSnapshot(forceRefresh))
        }

    @Test
    fun `root serves the react app shell`() {
        testApplication {
            application {
                transportModule(
                    snapshotService,
                    tubeLineMapService,
                    tubeMapFeedService,
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
                    tubeLineMapService,
                    tubeMapFeedService,
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
                    tubeLineMapService,
                    tubeMapFeedService,
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
                    StubTubeSnapshotService { forceRefresh ->
                        Failure(TransportError.SnapshotUnavailable("TfL unavailable"))
                    },
                    tubeLineMapService,
                    StubTubeMapFeedService(
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
                    tubeLineMapService,
                    tubeMapFeedService,
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
                    tubeLineMapService,
                    tubeMapFeedService,
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
            expectThat(body).contains("\"tubeStations\"")
        }
    }

    @Test
    fun `api keeps tube routes as aliases`() {
        testApplication {
            application {
                transportModule(
                    snapshotService,
                    tubeLineMapService,
                    tubeMapFeedService,
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
                    tubeLineMapService,
                    tubeMapFeedService,
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
                    tubeLineMapService,
                    StubTubeMapFeedService(
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
        LiveTubeSnapshot(
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
                LiveTubeTrain(
                    TrainId("257"),
                    VehicleId("257"),
                    listOf(LineId("victoria")),
                    listOf(LineName("Victoria")),
                    TrainDirection("outbound"),
                    DestinationName("Walthamstow Central Underground Station"),
                    TowardsDescription("Walthamstow Central"),
                    LocationDescription("Approaching Green Park"),
                    LocationEstimate(
                        LocationType.APPROACHING_STATION,
                        LocationDescription("Approaching Green Park"),
                        GeoCoordinate(51.506947, -0.142787),
                        StationReference(
                            StationId("940GZZLUGPK"),
                            StationName("Green Park Underground Station"),
                            GeoCoordinate(51.506947, -0.142787)
                        ),
                        null,
                        null
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
        TubeLineMap(
            listOf(
                TubeLine(
                    LineId("victoria"),
                    LineName("Victoria"),
                    listOf(
                        TubeLinePath(
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
        TubeMapSnapshot(
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
                TubeMapStation(
                    StationId("940GZZLUGPK"),
                    StationName("Green Park Underground Station"),
                    GeoCoordinate(51.506947, -0.142787),
                    listOf(LineId("victoria"))
                )
            ),
            listOf(
                TubeMapTrain(
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
}
