package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.hasSize
import strikt.assertions.isA

class RealRailMapServiceTest {
    private val railLineProjectionFactory: RailLineProjectionFactory =
        RealRailLineProjectionFactory()
    private val railMapProjector: RailMapProjector =
        RealRailMapProjector(RealIdentityRailPathSmoother(), railLineProjectionFactory)

    @Test
    fun `getRailMap combines snapshot and line geometry`() {
        runBlocking {
            val service = RealRailMapService(
                StubRailSnapshotService { forceRefresh ->
                    Success(sampleSnapshot())
                },
                StubRailLineMapService {
                    Success(sampleLineMap())
                },
                railMapProjector
            )

            val result = service.getRailMap(true)

            expectThat(result).isSuccess().get { lines }.hasSize(1)
            expectThat(result).isSuccess().get { trains }.hasSize(1)
        }
    }

    @Test
    fun `getRailMap returns snapshot failures unchanged`() {
        runBlocking {
            val service = RealRailMapService(
                StubRailSnapshotService { forceRefresh ->
                    Failure(TransportError.SnapshotUnavailable("TfL unavailable"))
                },
                StubRailLineMapService {
                    Success(sampleLineMap())
                },
                railMapProjector
            )

            val result = service.getRailMap(true)

            expectThat(result).isFailure().isA<TransportError.SnapshotUnavailable>()
        }
    }

    private fun sampleSnapshot() =
        LiveRailSnapshot(
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
                LiveRailTrain(
                    TrainId("victoria|257"),
                    VehicleId("257"),
                    listOf(LineId("victoria")),
                    listOf(LineName("Victoria")),
                    TrainDirection("outbound"),
                    DestinationName("Walthamstow Central Underground Station"),
                    TowardsDescription("Walthamstow Central"),
                    LocationDescription("At Alpha"),
                    LocationEstimate(
                        LocationType.STATION_BOARD,
                        LocationDescription("Alpha Underground Station"),
                        GeoCoordinate(51.0, -0.3),
                        StationReference(
                            StationId("A"),
                            StationName("Alpha Underground Station"),
                            GeoCoordinate(51.0, -0.3)
                        )
                    ),
                    null,
                    Duration.ofSeconds(45),
                    Instant.parse("2026-03-22T00:50:05Z"),
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
                                GeoCoordinate(51.0, -0.3),
                                GeoCoordinate(51.0, -0.2),
                                GeoCoordinate(51.1, -0.1)
                            )
                        )
                    ),
                    emptyList()
                )
            )
        )
}
