package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.isEqualTo

class ApiTest {
    private val serviceResponseMapper: ServiceResponseMapper = ServiceResponseMapperHttp()
    private val tubeLineMapService: TubeLineMapService =
        FakeLineMapService {
            Success(sampleLineMap())
        }
    private val tubeMapService: TubeMapService =
        FakeMapService { forceRefresh ->
            Success(sampleMap(forceRefresh))
        }

    @Test
    fun `root serves the react app shell`() {
        testApplication {
            application {
                transportModule(
                    FakeSnapshotService { forceRefresh ->
                        Success(sampleSnapshot(forceRefresh))
                    },
                    tubeLineMapService,
                    tubeMapService,
                    serviceResponseMapper,
                    transportJson()
                )
            }

            val response = client.get("/")

            expectThat(response.status).isEqualTo(HttpStatusCode.OK)
            expectThat(response.bodyAsText()).contains("London Tube Pulse")
        }
    }

    @Test
    fun `api description is available on api root`() {
        testApplication {
            application {
                transportModule(
                    FakeSnapshotService { forceRefresh ->
                        Success(sampleSnapshot(forceRefresh))
                    },
                    tubeLineMapService,
                    tubeMapService,
                    serviceResponseMapper,
                    transportJson()
                )
            }

            val response = client.get("/api")

            expectThat(response.status).isEqualTo(HttpStatusCode.OK)
            expectThat(response.bodyAsText()).contains("\"service\"")
        }
    }

    @Test
    fun `api returns live snapshot payload`() {
        testApplication {
            application {
                transportModule(
                    FakeSnapshotService { forceRefresh ->
                        Success(sampleSnapshot(forceRefresh))
                    },
                    tubeLineMapService,
                    tubeMapService,
                    serviceResponseMapper,
                    transportJson()
                )
            }

            val response = client.get("/api/tubes/live")

            expectThat(response.status).isEqualTo(HttpStatusCode.OK)
            expectThat(response.bodyAsText()).contains("\"trainCount\"")
        }
    }

    @Test
    fun `api maps transport errors to http response`() {
        testApplication {
            application {
                transportModule(
                    FakeSnapshotService { forceRefresh ->
                        Failure(TransportError.SnapshotUnavailable("TfL unavailable"))
                    },
                    tubeLineMapService,
                    FakeMapService { forceRefresh ->
                        Failure(TransportError.SnapshotUnavailable("TfL unavailable"))
                    },
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
    fun `api returns tube line map payload`() {
        testApplication {
            application {
                transportModule(
                    FakeSnapshotService { forceRefresh ->
                        Success(sampleSnapshot(forceRefresh))
                    },
                    tubeLineMapService,
                    tubeMapService,
                    serviceResponseMapper,
                    transportJson()
                )
            }

            val response = client.get("/api/tubes/lines")

            expectThat(response.status).isEqualTo(HttpStatusCode.OK)
            expectThat(response.bodyAsText()).contains("\"paths\"")
        }
    }

    @Test
    fun `api returns projected tube map payload`() {
        testApplication {
            application {
                transportModule(
                    FakeSnapshotService { forceRefresh ->
                        Success(sampleSnapshot(forceRefresh))
                    },
                    tubeLineMapService,
                    tubeMapService,
                    serviceResponseMapper,
                    transportJson()
                )
            }

            val response = client.get("/api/tubes/map")

            expectThat(response.status).isEqualTo(HttpStatusCode.OK)
            expectThat(response.bodyAsText()).contains("\"coordinate\"")
        }
    }

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
                TubeMapTrain(
                    TrainId("victoria|257"),
                    VehicleId("257"),
                    LineId("victoria"),
                    LineName("Victoria"),
                    TrainDirection("outbound"),
                    DestinationName("Walthamstow Central Underground Station"),
                    TowardsDescription("Walthamstow Central"),
                    LocationDescription("Approaching Green Park"),
                    GeoCoordinate(51.506947, -0.142787),
                    HeadingDegrees(42.0),
                    Duration.ofSeconds(90),
                    Instant.parse("2026-03-22T00:50:50Z"),
                    Instant.parse("2026-03-22T00:49:20Z")
                )
            )
        )
}

private class FakeSnapshotService(
    private val handler: suspend (Boolean) -> TransportResult<LiveTubeSnapshot>
) : TubeSnapshotService {
    override suspend fun getLiveSnapshot(forceRefresh: Boolean) =
        handler(forceRefresh)
}

private class FakeLineMapService(
    private val handler: suspend () -> TransportResult<TubeLineMap>
) : TubeLineMapService {
    override suspend fun getTubeLineMap() =
        handler()
}

private class FakeMapService(
    private val handler: suspend (Boolean) -> TransportResult<TubeMapSnapshot>
) : TubeMapService {
    override suspend fun getTubeMap(forceRefresh: Boolean) =
        handler(forceRefresh)
}
