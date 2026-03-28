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
    fun `mapResponse maps projected rail map to serializable json`() {
        val response = serviceResponseMapper.mapResponse(
            RailMapSnapshot(
                transportSourceName,
                Instant.parse("2026-03-22T00:49:20Z"),
                false,
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
                        listOf(LineId("victoria")),
                        listOf(
                            StationArrival(
                                TrainId("victoria|257"),
                                LineId("victoria"),
                                LineName("Victoria"),
                                DestinationName("Walthamstow Central Underground Station"),
                                Instant.parse("2026-03-22T00:50:50Z")
                            )
                        )
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
                        Instant.parse("2026-03-22T00:50:50Z"),
                        Instant.parse("2026-03-22T00:49:20Z"),
                        listOf(
                            FutureStationArrival(
                                StationId("940GZZLUOXC"),
                                StationName("Oxford Circus Underground Station"),
                                Instant.parse("2026-03-22T00:52:20Z")
                            )
                        )
                    )
                )
            )
        )

        expectThat(response.lines).hasSize(1)
        expectThat(response.stations).hasSize(1)
        expectThat(response.stations.first().name).isEqualTo("Green Park Underground Station")
        expectThat(response.stations.first().arrivals).hasSize(1)
        expectThat(response.stations.first().arrivals.first().lineId).isEqualTo("victoria")
        expectThat(response.trains.first().lineId).isEqualTo("victoria")
        expectThat(response.trains.first().coordinate).isNotNull().get { lat }.isEqualTo(51.506947)
        expectThat(response.trains.first().headingDegrees).isEqualTo(42.0)
        expectThat(response.trains.first().futureArrivals).hasSize(1)
        expectThat(response.trains.first().futureArrivals.first().stationName).isEqualTo("Oxford Circus Underground Station")
    }

    @Test
    fun `trainPositionsResponse omits static map geometry`() {
        val response = serviceResponseMapper.trainPositionsResponse(
            RailMapTrainPositions(
                transportSourceName,
                Instant.parse("2026-03-22T00:49:20Z"),
                true,
                Duration.ofSeconds(15),
                StationQueryCount(1),
                StationFailureCount(0),
                false,
                LiveTrainCount(1),
                listOf(
                    MapStation(
                        StationId("940GZZLUGPK"),
                        StationName("Green Park Underground Station"),
                        GeoCoordinate(51.506947, -0.142787),
                        listOf(LineId("victoria")),
                        listOf(
                            StationArrival(
                                TrainId("victoria|257"),
                                LineId("victoria"),
                                LineName("Victoria"),
                                DestinationName("Walthamstow Central Underground Station"),
                                Instant.parse("2026-03-22T00:50:50Z")
                            )
                        )
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
                        Instant.parse("2026-03-22T00:50:50Z"),
                        Instant.parse("2026-03-22T00:49:20Z"),
                        listOf(
                            FutureStationArrival(
                                StationId("940GZZLUOXC"),
                                StationName("Oxford Circus Underground Station"),
                                Instant.parse("2026-03-22T00:52:20Z")
                            )
                        )
                    )
                )
            )
        )

        expectThat(response.cacheAgeSeconds).isEqualTo(15)
        expectThat(response.stations).hasSize(1)
        expectThat(response.stations.first().arrivals.first().lineName).isEqualTo("Victoria")
        expectThat(response.trains).hasSize(1)
        expectThat(response.trains.first().lineId).isEqualTo("victoria")
        expectThat(response.trains.first().futureArrivals).hasSize(1)
    }

    @Test
    fun `errorResponse maps upstream http failures to api json`() {
        val response = serviceResponseMapper.errorResponse(
            TransportError.UpstreamHttpFailure("/Line/victoria/StopPoints", 503, "down")
        )

        expectThat(response.error).isEqualTo("upstream_http_failure")
        expectThat(response.message).contains("HTTP 503")
    }
}
