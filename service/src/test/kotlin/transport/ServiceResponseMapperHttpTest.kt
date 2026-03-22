package transport

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.get
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull

class ServiceResponseMapperHttpTest {
    private val serviceResponseMapper: ServiceResponseMapper = ServiceResponseMapperHttp()

    @Test
    fun `snapshotResponse maps domain snapshot to serializable json`() {
        val response = serviceResponseMapper.snapshotResponse(
            LiveTubeSnapshot(
                transportSourceName,
                Instant.parse("2026-03-22T00:49:20Z"),
                false,
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
        )

        expectThat(response.lines).contains("victoria")
        expectThat(response.trains.first().trainId).isEqualTo("257")
        expectThat(response.trains.first().location).get { type }.isEqualTo(LocationTypeJson.APPROACHING_STATION)
        expectThat(response.trains.first().nextStop).isNotNull().get { id }.isEqualTo("940GZZLUGPK")
    }

    @Test
    fun `errorResponse maps upstream http failures to api json`() {
        val response = serviceResponseMapper.errorResponse(
            TransportError.UpstreamHttpFailure("/Line/victoria/StopPoints", 503, "down")
        )

        expectThat(response.error).isEqualTo("upstream_http_failure")
        expectThat(response.message).contains("HTTP 503")
    }

    @Test
    fun `healthResponse uses the supplied timestamp`() {
        val response = serviceResponseMapper.healthResponse(Instant.parse("2026-03-22T00:49:20Z"))

        expectThat(response.status).isEqualTo("ok")
        expectThat(response.generatedAt).isEqualTo("2026-03-22T00:49:20Z")
    }
}
