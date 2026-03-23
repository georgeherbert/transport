package transport

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.get
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull

class ServiceResponseMapperHttpTest {
    private val serviceResponseMapper: ServiceResponseMapper = ServiceResponseMapperHttp()

    @Test
    fun `mapResponse maps projected tube map to serializable json`() {
        val response = serviceResponseMapper.mapResponse(
            TubeMapSnapshot(
                transportSourceName,
                Instant.parse("2026-03-22T00:49:20Z"),
                false,
                Duration.ZERO,
                StationQueryCount(1),
                StationFailureCount(0),
                false,
                LiveTrainCount(1),
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
        )

        expectThat(response.lines).hasSize(1)
        expectThat(response.stations).hasSize(1)
        expectThat(response.stations.first().name).isEqualTo("Green Park Underground Station")
        expectThat(response.trains.first().lineId).isEqualTo("victoria")
        expectThat(response.trains.first().coordinate).isNotNull().get { lat }.isEqualTo(51.506947)
        expectThat(response.trains.first().headingDegrees).isEqualTo(42.0)
    }

    @Test
    fun `lineMapResponse maps domain line geometry to serializable json`() {
        val response = serviceResponseMapper.lineMapResponse(
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
        )

        expectThat(response.lines).hasSize(1)
        expectThat(response.lines.first().id).isEqualTo("victoria")
        expectThat(response.lines.first().paths.first().coordinates.first().lat).isEqualTo(51.496359)
    }

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
        )

        expectThat(response.lines).contains("victoria")
        expectThat(response.trains.first().trainId).isEqualTo("257")
        expectThat(response.trains.first().location).get { type }.isEqualTo(LocationTypeJson.STATION_BOARD)
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
