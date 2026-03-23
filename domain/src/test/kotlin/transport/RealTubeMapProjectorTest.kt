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
    fun `project snaps next-stop anchored trains onto the line path`() {
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
        expectThat(projected.stations).hasSize(3)
        expectThat(projected.lines.first().paths.first().coordinates.size).isGreaterThan(3)
        expectThat(projected.trains).hasSize(1)
        expectThat(projected.trains.first().coordinate).isEqualTo(GeoCoordinate(51.0, -0.2))
        expectThat(projected.trains.first().heading).isNotNull()
    }

    @Test
    fun `project includes configured map stations and excludes unsupported lines`() {
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
                ),
                TubeLine(
                    LineId("dlr"),
                    LineName("DLR"),
                    listOf(
                        TubeLinePath(
                            listOf(
                                GeoCoordinate(51.5, -0.1),
                                GeoCoordinate(51.6, 0.0)
                            )
                        )
                    ),
                    listOf(
                        TubeLineSequence(
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
                TubeLine(
                    LineId("elizabeth"),
                    LineName("Elizabeth line"),
                    listOf(
                        TubeLinePath(
                            listOf(
                                GeoCoordinate(51.5, -0.3),
                                GeoCoordinate(51.5, -0.2)
                            )
                        )
                    ),
                    listOf(
                        TubeLineSequence(
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
                TubeLine(
                    LineId("tram"),
                    LineName("Tram"),
                    listOf(
                        TubeLinePath(
                            listOf(
                                GeoCoordinate(51.35, -0.1),
                                GeoCoordinate(51.36, -0.05)
                            )
                        )
                    ),
                    listOf(
                        TubeLineSequence(
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

        val projected = RealTubeMapProjector(RealIdentityTubePathSmoother()).project(snapshot, lineMap)

        expectThat(projected.stations).hasSize(6)
        expectThat(projected.stations.first().name.value).isEqualTo("Alpha Underground Station")
        expectThat(projected.stations.first().coordinate.lat).isEqualTo(51.0)
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
                        LocationType.STATION_BOARD,
                        LocationDescription("Branch Station Underground Station"),
                        GeoCoordinate(51.5, 0.5),
                        StationReference(
                            StationId("C"),
                            StationName("Branch Station Underground Station"),
                            GeoCoordinate(51.5, 0.5)
                        )
                    ),
                    StationReference(
                        StationId("C"),
                        StationName("Branch Station Underground Station"),
                        GeoCoordinate(51.5, 0.5)
                    ),
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
                        LocationType.STATION_BOARD,
                        LocationDescription("Alpha Underground Station"),
                        GeoCoordinate(51.5, 0.5),
                        StationReference(
                            StationId("A"),
                            StationName("Alpha Underground Station"),
                            GeoCoordinate(51.5, 0.5)
                        )
                    ),
                    StationReference(
                        StationId("A"),
                        StationName("Alpha Underground Station"),
                        GeoCoordinate(51.5, 0.5)
                    ),
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

    @Test
    fun `headingAtProgress respects reverse travel direction`() {
        val projectedPath = ProjectedTubeLinePath(
            TubeLinePath(
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
}
