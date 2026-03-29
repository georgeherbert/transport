package transport

import java.time.Instant
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.hasSize
import strikt.assertions.isA

class RealRailMapQueryTest {
    private val railSnapshotService = StubRailSnapshotService()
    private val railLineMapService = StubRailLineMapService()
    private val railMapProjector = StubRailMapProjector()
    private val service = RealRailMapQuery(railSnapshotService, railLineMapService, railMapProjector)

    @Test
    fun `getRailMap combines snapshot and line geometry`() {
        runBlocking {
            railSnapshotService.returns(sampleSnapshot())
            railLineMapService.returns(sampleLineMap())

            val result = service.getRailMap(true)

            expectThat(result).isSuccess().get { lines }.hasSize(1)
            expectThat(result).isSuccess().get { services }.hasSize(1)
        }
    }

    @Test
    fun `getRailMap returns snapshot failures unchanged`() {
        runBlocking {
            railSnapshotService.failsWith(TransportError.SnapshotUnavailable("TfL unavailable"))
            railLineMapService.returns(sampleLineMap())

            val result = service.getRailMap(true)

            expectThat(result).isFailure().isA<TransportError.SnapshotUnavailable>()
        }
    }

    private fun sampleSnapshot() =
        LiveRailSnapshot(
            Instant.parse("2026-03-22T00:49:20Z"),
            StationFailureCount(0),
            false,
            LiveServiceCount(1),
            listOf(LineId("victoria")),
            listOf(
                LiveRailService(
                    ServiceId("257"),
                    listOf(LineId("victoria")),
                    listOf(LineName("Victoria")),
                    ServiceDirection("outbound"),
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
                    Instant.parse("2026-03-22T00:50:05Z"),
                    emptyList()
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
