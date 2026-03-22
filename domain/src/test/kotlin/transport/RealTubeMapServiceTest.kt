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

class RealTubeMapServiceTest {
    private val tubeMapProjector: TubeMapProjector =
        RealTubeMapProjector(RealTubePathSmoother(4))

    @Test
    fun `getTubeMap combines snapshot and line geometry`() {
        runBlocking {
            val service = RealTubeMapService(
                StubTubeSnapshotService { forceRefresh ->
                    Success(sampleSnapshot())
                },
                StubTubeLineMapService {
                    Success(sampleLineMap())
                },
                tubeMapProjector
            )

            val result = service.getTubeMap(true)

            expectThat(result).isSuccess().get { lines }.hasSize(1)
            expectThat(result).isSuccess().get { trains }.hasSize(1)
        }
    }

    @Test
    fun `getTubeMap returns snapshot failures unchanged`() {
        runBlocking {
            val service = RealTubeMapService(
                StubTubeSnapshotService { forceRefresh ->
                    Failure(TransportError.SnapshotUnavailable("TfL unavailable"))
                },
                StubTubeLineMapService {
                    Success(sampleLineMap())
                },
                tubeMapProjector
            )

            val result = service.getTubeMap(true)

            expectThat(result).isFailure().isA<TransportError.SnapshotUnavailable>()
        }
    }

    private fun sampleSnapshot() =
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
                    TrainId("victoria|257"),
                    VehicleId("257"),
                    listOf(LineId("victoria")),
                    listOf(LineName("Victoria")),
                    TrainDirection("outbound"),
                    DestinationName("Walthamstow Central Underground Station"),
                    TowardsDescription("Walthamstow Central"),
                    LocationDescription("At Alpha"),
                    LocationEstimate(
                        LocationType.AT_STATION,
                        LocationDescription("At Alpha"),
                        GeoCoordinate(51.0, -0.3),
                        StationReference(
                            StationId("A"),
                            StationName("Alpha Underground Station"),
                            GeoCoordinate(51.0, -0.3)
                        ),
                        null,
                        null
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
        TubeLineMap(
            listOf(
                TubeLine(
                    LineId("victoria"),
                    LineName("Victoria"),
                    listOf(
                        TubeLinePath(
                            listOf(
                                GeoCoordinate(51.0, -0.3),
                                GeoCoordinate(51.0, -0.2),
                                GeoCoordinate(51.1, -0.1)
                            )
                        )
                    )
                )
            )
        )
}
