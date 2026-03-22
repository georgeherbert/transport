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

    @Test
    fun `root serves the react app shell`() {
        testApplication {
            application {
                transportModule(
                    FakeSnapshotService { forceRefresh ->
                        Success(sampleSnapshot(forceRefresh))
                    },
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
                    serviceResponseMapper,
                    transportJson()
                )
            }

            val response = client.get("/api/tubes/live")

            expectThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
            expectThat(response.bodyAsText()).contains("snapshot_unavailable")
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
}

private class FakeSnapshotService(
    private val handler: suspend (Boolean) -> TransportResult<LiveTubeSnapshot>
) : TubeSnapshotService {
    override suspend fun getLiveSnapshot(forceRefresh: Boolean) =
        handler(forceRefresh)
}
