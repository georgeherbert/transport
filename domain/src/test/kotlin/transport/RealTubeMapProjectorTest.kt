package transport

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.get
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isGreaterThan
import strikt.assertions.isLessThan
import strikt.assertions.isNotNull

class RealTubeMapProjectorTest {
    private val projector: TubeMapProjector =
        RealTubeMapProjector(RealTubePathSmoother(6))

    @Test
    fun `identity smoother preserves imported line geometry`() {
        val smoother: TubePathSmoother = RealIdentityTubePathSmoother()
        val lineMap = TubeLineMap(
            listOf(
                TubeLine(
                    LineId("victoria"),
                    LineName("Victoria"),
                    listOf(
                        TubeLinePath(
                            listOf(
                                GeoCoordinate(51.0, -0.3),
                                GeoCoordinate(51.1, -0.2),
                                GeoCoordinate(51.2, -0.1)
                            )
                        )
                    ),
                    emptyList()
                )
            )
        )

        val smoothed = smoother.smooth(lineMap)

        expectThat(smoothed).isEqualTo(lineMap)
    }

    @Test
    fun `path smoother densifies the line path while preserving anchor points`() {
        val smoother: TubePathSmoother = RealTubePathSmoother(6)
        val lineMap = TubeLineMap(
            listOf(
                TubeLine(
                    LineId("victoria"),
                    LineName("Victoria"),
                    listOf(
                        TubeLinePath(
                            listOf(
                                GeoCoordinate(51.0, -0.3),
                                GeoCoordinate(51.1, -0.2),
                                GeoCoordinate(51.2, -0.1)
                            )
                        )
                    ),
                    emptyList()
                )
            )
        )

        val smoothed = smoother.smooth(lineMap)

        expectThat(smoothed.lines.first().paths.first().coordinates.size).isGreaterThan(3)
        expectThat(smoothed.lines.first().paths.first().coordinates).contains(
            GeoCoordinate(51.0, -0.3),
            GeoCoordinate(51.1, -0.2),
            GeoCoordinate(51.2, -0.1)
        )
    }

    @Test
    fun `project places between-station trains on the smoothed line path`() {
        val snapshot = LiveTubeSnapshot(
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
                    LocationDescription("Between Alpha and Bravo"),
                    LocationEstimate(
                        LocationType.BETWEEN_STATIONS,
                        LocationDescription("Between Alpha and Bravo"),
                        GeoCoordinate(51.0, -0.25),
                        null,
                        StationReference(
                            StationId("A"),
                            StationName("Alpha Underground Station"),
                            GeoCoordinate(51.0, -0.3)
                        ),
                        StationReference(
                            StationId("B"),
                            StationName("Bravo Underground Station"),
                            GeoCoordinate(51.0, -0.2)
                        )
                    ),
                    null,
                    Duration.ofSeconds(90),
                    Instant.parse("2026-03-22T00:50:50Z"),
                    Instant.parse("2026-03-22T00:49:20Z"),
                    PredictionCount(1)
                )
            )
        )
        val lineMap = TubeLineMap(
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
                    ),
                    listOf(
                        TubeLineSequence(
                            TrainDirection("outbound"),
                            listOf(
                                StationReference(
                                    StationId("A"),
                                    StationName("Alpha Underground Station"),
                                    GeoCoordinate(51.0, -0.3)
                                ),
                                StationReference(
                                    StationId("B"),
                                    StationName("Bravo Underground Station"),
                                    GeoCoordinate(51.0, -0.2)
                                ),
                                StationReference(
                                    StationId("C"),
                                    StationName("Charlie Underground Station"),
                                    GeoCoordinate(51.1, -0.1)
                                )
                            )
                        )
                    )
                )
            )
        )

        val projected = projector.project(snapshot, lineMap)

        expectThat(projected.lines).hasSize(1)
        expectThat(projected.tubeStations).hasSize(3)
        expectThat(projected.lines.first().paths.first().coordinates.size).isGreaterThan(3)
        expectThat(projected.trains).hasSize(1)
        expectThat(projected.trains.first().coordinate).isNotNull().get { lat }.isLessThan(51.01)
        expectThat(projected.trains.first().coordinate).isNotNull().get { lat }.isGreaterThan(50.99)
        expectThat(projected.trains.first().coordinate).isNotNull().get { lon }.isLessThan(-0.2)
        expectThat(projected.trains.first().coordinate).isNotNull().get { lon }.isGreaterThan(-0.3)
        expectThat(projected.trains.first().heading).isNotNull().get { value }.isGreaterThan(80.0)
        expectThat(projected.trains.first().heading).isNotNull().get { value }.isLessThan(100.0)
    }

    @Test
    fun `project includes projected tube stations and excludes non tube lines`() {
        val snapshot = LiveTubeSnapshot(
            transportSourceName,
            Instant.parse("2026-03-22T00:49:20Z"),
            false,
            Duration.ZERO,
            StationQueryCount(1),
            StationFailureCount(0),
            false,
            LiveTrainCount(0),
            emptyList(),
            emptyList()
        )
        val lineMap = TubeLineMap(
            listOf(
                TubeLine(
                    LineId("victoria"),
                    LineName("Victoria"),
                    listOf(
                        TubeLinePath(
                            listOf(
                                GeoCoordinate(51.0, -0.3),
                                GeoCoordinate(51.0, -0.2)
                            )
                        )
                    ),
                    listOf(
                        TubeLineSequence(
                            TrainDirection("outbound"),
                            listOf(
                                StationReference(
                                    StationId("A"),
                                    StationName("Alpha Underground Station"),
                                    GeoCoordinate(51.01, -0.3)
                                ),
                                StationReference(
                                    StationId("B"),
                                    StationName("Bravo Underground Station"),
                                    GeoCoordinate(51.01, -0.2)
                                )
                            )
                        )
                    )
                ),
                TubeLine(
                    LineId("mildmay"),
                    LineName("Mildmay"),
                    listOf(
                        TubeLinePath(
                            listOf(
                                GeoCoordinate(51.4, 0.1),
                                GeoCoordinate(51.5, 0.2)
                            )
                        )
                    ),
                    listOf(
                        TubeLineSequence(
                            TrainDirection("outbound"),
                            listOf(
                                StationReference(
                                    StationId("C"),
                                    StationName("Charlie"),
                                    GeoCoordinate(51.45, 0.15)
                                )
                            )
                        )
                    )
                )
            )
        )

        val projected = RealTubeMapProjector(RealIdentityTubePathSmoother()).project(snapshot, lineMap)

        expectThat(projected.tubeStations).hasSize(2)
        expectThat(projected.tubeStations.first().name.value).isEqualTo("Alpha Underground Station")
        expectThat(projected.tubeStations.first().coordinate.lat).isEqualTo(51.0)
    }

    @Test
    fun `project snaps station based trains onto the closest branch of the selected line`() {
        val snapshot = LiveTubeSnapshot(
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
                    TrainId("victoria|300"),
                    VehicleId("300"),
                    listOf(LineId("victoria")),
                    listOf(LineName("Victoria")),
                    TrainDirection("outbound"),
                    DestinationName("Walthamstow Central Underground Station"),
                    TowardsDescription("Walthamstow Central"),
                    LocationDescription("At Branch Station"),
                    LocationEstimate(
                        LocationType.AT_STATION,
                        LocationDescription("At Branch Station"),
                        GeoCoordinate(51.5, 0.5),
                        StationReference(
                            StationId("C"),
                            StationName("Branch Station Underground Station"),
                            GeoCoordinate(51.5, 0.5)
                        ),
                        null,
                        null
                    ),
                    null,
                    Duration.ofSeconds(30),
                    Instant.parse("2026-03-22T00:49:50Z"),
                    Instant.parse("2026-03-22T00:49:20Z"),
                    PredictionCount(1)
                )
            )
        )
        val lineMap = TubeLineMap(
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
                        ),
                        TubeLinePath(
                            listOf(
                                GeoCoordinate(51.4, 0.4),
                                GeoCoordinate(51.5, 0.5),
                                GeoCoordinate(51.6, 0.6)
                            )
                        )
                    ),
                    listOf(
                        TubeLineSequence(
                            TrainDirection("outbound"),
                            listOf(
                                StationReference(
                                    StationId("C"),
                                    StationName("Branch Station Underground Station"),
                                    GeoCoordinate(51.5, 0.5)
                                ),
                                StationReference(
                                    StationId("D"),
                                    StationName("Downstream Underground Station"),
                                    GeoCoordinate(51.6, 0.6)
                                )
                            )
                        )
                    )
                )
            )
        )

        val projected = projector.project(snapshot, lineMap)

        expectThat(projected.trains.first().coordinate).isEqualTo(GeoCoordinate(51.5, 0.5))
        expectThat(projected.trains.first().heading).isNotNull().get { value }.isGreaterThan(30.0)
        expectThat(projected.trains.first().heading).isNotNull().get { value }.isLessThan(60.0)
    }

    @Test
    fun `project uses map projection for heading so diagonal trains align with rendered paths`() {
        val snapshot = LiveTubeSnapshot(
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
                    TrainId("victoria|401"),
                    VehicleId("401"),
                    listOf(LineId("victoria")),
                    listOf(LineName("Victoria")),
                    TrainDirection("outbound"),
                    DestinationName("Downstream Underground Station"),
                    TowardsDescription("Downstream"),
                    LocationDescription("At Alpha"),
                    LocationEstimate(
                        LocationType.AT_STATION,
                        LocationDescription("At Alpha"),
                        GeoCoordinate(51.5, 0.5),
                        StationReference(
                            StationId("A"),
                            StationName("Alpha Underground Station"),
                            GeoCoordinate(51.5, 0.5)
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
        val lineMap = TubeLineMap(
            listOf(
                TubeLine(
                    LineId("victoria"),
                    LineName("Victoria"),
                    listOf(
                        TubeLinePath(
                            listOf(
                                GeoCoordinate(51.5, 0.5),
                                GeoCoordinate(51.6, 0.6)
                            )
                        )
                    ),
                    listOf(
                        TubeLineSequence(
                            TrainDirection("outbound"),
                            listOf(
                                StationReference(
                                    StationId("A"),
                                    StationName("Alpha Underground Station"),
                                    GeoCoordinate(51.5, 0.5)
                                ),
                                StationReference(
                                    StationId("B"),
                                    StationName("Downstream Underground Station"),
                                    GeoCoordinate(51.6, 0.6)
                                )
                            )
                        )
                    )
                )
            )
        )

        val projected = projector.project(snapshot, lineMap)

        expectThat(projected.trains.first().heading).isNotNull().get { value }.isGreaterThan(30.0)
        expectThat(projected.trains.first().heading).isNotNull().get { value }.isLessThan(35.0)
    }
}
