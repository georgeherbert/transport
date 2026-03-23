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
import strikt.assertions.isNull

class RealRailMapProjectorTest {
    private val projector: RailMapProjector =
        RealRailMapProjector(RealRailPathSmoother(6))

    @Test
    fun `identity smoother preserves imported line geometry`() {
        val smoother: RailPathSmoother = RealIdentityRailPathSmoother()
        val lineMap = RailLineMap(
            listOf(
                RailLine(
                    LineId("victoria"),
                    LineName("Victoria"),
                    listOf(
                        RailLinePath(
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
        val smoother: RailPathSmoother = RealRailPathSmoother(6)
        val lineMap = RailLineMap(
            listOf(
                RailLine(
                    LineId("victoria"),
                    LineName("Victoria"),
                    listOf(
                        RailLinePath(
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
    fun `project anchors trains at the next stop when no timing is learned`() {
        val snapshot = LiveRailSnapshot(
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
                    LocationDescription("Bravo Underground Station"),
                    LocationEstimate(
                        LocationType.STATION_BOARD,
                        LocationDescription("Bravo Underground Station"),
                        GeoCoordinate(51.0, -0.2),
                        StationReference(
                            StationId("B"),
                            StationName("Bravo Underground Station"),
                            GeoCoordinate(51.0, -0.2)
                        )
                    ),
                    StationReference(
                        StationId("B"),
                        StationName("Bravo Underground Station"),
                        GeoCoordinate(51.0, -0.2)
                    ),
                    Duration.ofSeconds(90),
                    Instant.parse("2026-03-22T00:50:50Z"),
                    Instant.parse("2026-03-22T00:49:20Z"),
                    PredictionCount(1)
                )
            )
        )
        val lineMap = RailLineMap(
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
                    listOf(
                        RailLineSequence(
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
        expectThat(projected.stations).hasSize(3)
        expectThat(projected.lines.first().paths.first().coordinates.size).isGreaterThan(3)
        expectThat(projected.trains).hasSize(1)
        expectThat(projected.trains.first().coordinate).isEqualTo(GeoCoordinate(51.0, -0.2))
        expectThat(projected.trains.first().heading).isNotNull()
    }

    @Test
    fun `project leaves trains unplotted when next stop is unknown even if a location coordinate exists`() {
        val snapshot = LiveRailSnapshot(
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
                    TrainId("victoria|258"),
                    VehicleId("258"),
                    listOf(LineId("victoria")),
                    listOf(LineName("Victoria")),
                    TrainDirection("outbound"),
                    DestinationName("Walthamstow Central Underground Station"),
                    TowardsDescription("Walthamstow Central"),
                    LocationDescription("Between Alpha and Bravo"),
                    LocationEstimate(
                        LocationType.STATION_BOARD,
                        LocationDescription("Between Alpha and Bravo"),
                        GeoCoordinate(51.0, -0.25),
                        StationReference(
                            StationId("A"),
                            StationName("Alpha Underground Station"),
                            GeoCoordinate(51.0, -0.3)
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
        val lineMap = RailLineMap(
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
                    listOf(
                        RailLineSequence(
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

        expectThat(projected.trains.first().coordinate).isNull()
        expectThat(projected.trains.first().heading).isNull()
    }

    @Test
    fun `project anchors trains at the next stop when the next stop is first in the sequence`() {
        val snapshot = LiveRailSnapshot(
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
                    TrainId("victoria|259"),
                    VehicleId("259"),
                    listOf(LineId("victoria")),
                    listOf(LineName("Victoria")),
                    TrainDirection("outbound"),
                    DestinationName("Walthamstow Central Underground Station"),
                    TowardsDescription("Walthamstow Central"),
                    LocationDescription("Alpha Underground Station"),
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
                    StationReference(
                        StationId("A"),
                        StationName("Alpha Underground Station"),
                        GeoCoordinate(51.0, -0.3)
                    ),
                    Duration.ofSeconds(90),
                    Instant.parse("2026-03-22T00:50:50Z"),
                    Instant.parse("2026-03-22T00:49:20Z"),
                    PredictionCount(1)
                )
            )
        )
        val lineMap = RailLineMap(
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
                    listOf(
                        RailLineSequence(
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

        expectThat(projected.trains.first().coordinate).isEqualTo(GeoCoordinate(51.0, -0.3))
        expectThat(projected.trains.first().heading).isNotNull()
    }

    @Test
    fun `project anchors trains at the next stop even when direction is unknown`() {
        val snapshot = LiveRailSnapshot(
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
                    TrainId("victoria|260"),
                    VehicleId("260"),
                    listOf(LineId("victoria")),
                    listOf(LineName("Victoria")),
                    null,
                    DestinationName("Walthamstow Central Underground Station"),
                    TowardsDescription("Walthamstow Central"),
                    LocationDescription("Bravo Underground Station"),
                    LocationEstimate(
                        LocationType.STATION_BOARD,
                        LocationDescription("Bravo Underground Station"),
                        GeoCoordinate(51.0, -0.2),
                        StationReference(
                            StationId("B"),
                            StationName("Bravo Underground Station"),
                            GeoCoordinate(51.0, -0.2)
                        )
                    ),
                    StationReference(
                        StationId("B"),
                        StationName("Bravo Underground Station"),
                        GeoCoordinate(51.0, -0.2)
                    ),
                    Duration.ofSeconds(90),
                    Instant.parse("2026-03-22T00:50:50Z"),
                    Instant.parse("2026-03-22T00:49:20Z"),
                    PredictionCount(1)
                )
            )
        )
        val lineMap = RailLineMap(
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
                    listOf(
                        RailLineSequence(
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

        expectThat(projected.trains.first().coordinate).isEqualTo(GeoCoordinate(51.0, -0.2))
        expectThat(projected.trains.first().heading).isNull()
    }

    @Test
    fun `project includes configured map stations and excludes unsupported lines`() {
        val snapshot = LiveRailSnapshot(
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
        val lineMap = RailLineMap(
            listOf(
                RailLine(
                    LineId("victoria"),
                    LineName("Victoria"),
                    listOf(
                        RailLinePath(
                            listOf(
                                GeoCoordinate(51.0, -0.3),
                                GeoCoordinate(51.0, -0.2)
                            )
                        )
                    ),
                    listOf(
                        RailLineSequence(
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
                RailLine(
                    LineId("mildmay"),
                    LineName("Mildmay"),
                    listOf(
                        RailLinePath(
                            listOf(
                                GeoCoordinate(51.4, 0.1),
                                GeoCoordinate(51.5, 0.2)
                            )
                        )
                    ),
                    listOf(
                        RailLineSequence(
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
                ),
                RailLine(
                    LineId("dlr"),
                    LineName("DLR"),
                    listOf(
                        RailLinePath(
                            listOf(
                                GeoCoordinate(51.5, -0.1),
                                GeoCoordinate(51.6, 0.0)
                            )
                        )
                    ),
                    listOf(
                        RailLineSequence(
                            TrainDirection("outbound"),
                            listOf(
                                StationReference(
                                    StationId("D"),
                                    StationName("Delta DLR Station"),
                                    GeoCoordinate(51.55, -0.05)
                                )
                            )
                        )
                    )
                ),
                RailLine(
                    LineId("elizabeth"),
                    LineName("Elizabeth line"),
                    listOf(
                        RailLinePath(
                            listOf(
                                GeoCoordinate(51.5, -0.3),
                                GeoCoordinate(51.5, -0.2)
                            )
                        )
                    ),
                    listOf(
                        RailLineSequence(
                            TrainDirection("outbound"),
                            listOf(
                                StationReference(
                                    StationId("E"),
                                    StationName("Echo Elizabeth Line Station"),
                                    GeoCoordinate(51.51, -0.25)
                                )
                            )
                        )
                    )
                ),
                RailLine(
                    LineId("tram"),
                    LineName("Tram"),
                    listOf(
                        RailLinePath(
                            listOf(
                                GeoCoordinate(51.35, -0.1),
                                GeoCoordinate(51.36, -0.05)
                            )
                        )
                    ),
                    listOf(
                        RailLineSequence(
                            TrainDirection("outbound"),
                            listOf(
                                StationReference(
                                    StationId("F"),
                                    StationName("Foxtrot Tram Stop"),
                                    GeoCoordinate(51.355, -0.075)
                                )
                            )
                        )
                    )
                )
            )
        )

        val projected = RealRailMapProjector(RealIdentityRailPathSmoother()).project(snapshot, lineMap)

        expectThat(projected.stations).hasSize(5)
        expectThat(projected.stations.first().name.value).isEqualTo("Alpha Underground Station")
        expectThat(projected.stations.first().coordinate.lat).isEqualTo(51.0)
        expectThat(projected.stations.map { station -> station.name.value }).not().contains("Delta DLR Station")
    }

    @Test
    fun `project snaps station based trains onto the closest branch of the selected line`() {
        val snapshot = LiveRailSnapshot(
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
                    TrainId("victoria|300"),
                    VehicleId("300"),
                    listOf(LineId("victoria")),
                    listOf(LineName("Victoria")),
                    TrainDirection("outbound"),
                    DestinationName("Walthamstow Central Underground Station"),
                    TowardsDescription("Walthamstow Central"),
                    LocationDescription("Approaching Downstream Underground Station"),
                    LocationEstimate(
                        LocationType.STATION_BOARD,
                        LocationDescription("Downstream Underground Station"),
                        GeoCoordinate(51.6, 0.6),
                        StationReference(
                            StationId("D"),
                            StationName("Downstream Underground Station"),
                            GeoCoordinate(51.6, 0.6)
                        )
                    ),
                    StationReference(
                        StationId("D"),
                        StationName("Downstream Underground Station"),
                        GeoCoordinate(51.6, 0.6)
                    ),
                    Duration.ofSeconds(30),
                    Instant.parse("2026-03-22T00:49:50Z"),
                    Instant.parse("2026-03-22T00:49:20Z"),
                    PredictionCount(1)
                )
            )
        )
        val lineMap = RailLineMap(
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
                        ),
                        RailLinePath(
                            listOf(
                                GeoCoordinate(51.4, 0.4),
                                GeoCoordinate(51.5, 0.5),
                                GeoCoordinate(51.6, 0.6)
                            )
                        )
                    ),
                    listOf(
                        RailLineSequence(
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

        expectThat(projected.trains.first().coordinate).isEqualTo(GeoCoordinate(51.6, 0.6))
        expectThat(projected.trains.first().heading).isNotNull().get { value }.isGreaterThan(30.0)
        expectThat(projected.trains.first().heading).isNotNull().get { value }.isLessThan(60.0)
    }

    @Test
    fun `project uses map projection for heading so diagonal trains align with rendered paths`() {
        val snapshot = LiveRailSnapshot(
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
                    TrainId("victoria|401"),
                    VehicleId("401"),
                    listOf(LineId("victoria")),
                    listOf(LineName("Victoria")),
                    TrainDirection("outbound"),
                    DestinationName("Downstream Underground Station"),
                    TowardsDescription("Downstream"),
                    LocationDescription("Approaching Downstream Underground Station"),
                    LocationEstimate(
                        LocationType.STATION_BOARD,
                        LocationDescription("Downstream Underground Station"),
                        GeoCoordinate(51.6, 0.6),
                        StationReference(
                            StationId("B"),
                            StationName("Downstream Underground Station"),
                            GeoCoordinate(51.6, 0.6)
                        )
                    ),
                    StationReference(
                        StationId("B"),
                        StationName("Downstream Underground Station"),
                        GeoCoordinate(51.6, 0.6)
                    ),
                    Duration.ofSeconds(45),
                    Instant.parse("2026-03-22T00:50:05Z"),
                    Instant.parse("2026-03-22T00:49:20Z"),
                    PredictionCount(1)
                )
            )
        )
        val lineMap = RailLineMap(
            listOf(
                RailLine(
                    LineId("victoria"),
                    LineName("Victoria"),
                    listOf(
                        RailLinePath(
                            listOf(
                                GeoCoordinate(51.5, 0.5),
                                GeoCoordinate(51.6, 0.6)
                            )
                        )
                    ),
                    listOf(
                        RailLineSequence(
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

        expectThat(projected.trains.first().coordinate).isEqualTo(GeoCoordinate(51.6, 0.6))
        expectThat(projected.trains.first().heading).isNotNull().get { value }.isGreaterThan(30.0)
        expectThat(projected.trains.first().heading).isNotNull().get { value }.isLessThan(35.0)
    }

    @Test
    fun `headingAtProgress respects reverse travel direction`() {
        val projectedPath = ProjectedRailLinePath(
            RailLinePath(
                listOf(
                    GeoCoordinate(51.5, -0.3),
                    GeoCoordinate(51.5, -0.2)
                )
            )
        )

        val heading = projectedPath.headingAtProgress(projectedPath.totalLength, 0.0, 0.5)

        expectThat(heading).isNotNull().get { value }.isGreaterThan(260.0)
        expectThat(heading).isNotNull().get { value }.isLessThan(280.0)
    }

    @Test
    fun `headingAtProgress keeps reverse travel direction at station arrival`() {
        val projectedPath = ProjectedRailLinePath(
            RailLinePath(
                listOf(
                    GeoCoordinate(51.5, -0.3),
                    GeoCoordinate(51.5, -0.2)
                )
            )
        )

        val heading = projectedPath.headingAtProgress(projectedPath.totalLength, 0.0, 1.0)

        expectThat(heading).isNotNull().get { value }.isGreaterThan(260.0)
        expectThat(heading).isNotNull().get { value }.isLessThan(280.0)
    }
}
