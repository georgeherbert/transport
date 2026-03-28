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
    private val nextStop = StationReference(
        StationId("940GZZLUGPK"),
        StationName("Green Park Underground Station"),
        GeoCoordinate(51.506947, -0.142787)
    )
    private val originalLineMap = RailLineMap(
        listOf(
            RailLine(
                LineId("victoria"),
                LineName("Victoria"),
                listOf(
                    RailLinePath(
                        listOf(
                            GeoCoordinate(51.500000, -0.150000),
                            GeoCoordinate(51.510000, -0.140000)
                        )
                    )
                ),
                listOf(
                    RailLineSequence(
                        TrainDirection("outbound"),
                        listOf(nextStop)
                    )
                )
            )
        )
    )
    private val smoothedLineMap = RailLineMap(
        listOf(
            RailLine(
                LineId("victoria"),
                LineName("Victoria"),
                listOf(
                    RailLinePath(
                        listOf(
                            GeoCoordinate(51.501000, -0.149000),
                            GeoCoordinate(51.509000, -0.141000)
                        )
                    )
                ),
                listOf(
                    RailLineSequence(
                        TrainDirection("outbound"),
                        listOf(nextStop)
                    )
                )
            )
        )
    )
    private val seamProjection = StubRailLineProjection(smoothedLineMap.lines.first())
    private val seamPathSmoother = StubRailPathSmoother()
    private val seamProjectionFactory = StubRailLineProjectionFactory()
    private val seamProjector: RailMapProjector =
        RealRailMapProjector(seamPathSmoother, seamProjectionFactory)
    private val linePath = RailLinePath(
        listOf(
            GeoCoordinate(51.500000, -0.150000),
            GeoCoordinate(51.510000, -0.140000)
        )
    )
    private val line = RailLine(
        LineId("victoria"),
        LineName("Victoria"),
        listOf(linePath),
        emptyList()
    )
    private val pathProjection = StubRailLinePathProjection(linePath, 100.0)
    private val pathProjectionFactory = StubRailLinePathProjectionFactory()
    private val lineProjectionUnderTest: RailLineProjection
    private val railLineProjectionFactory: RailLineProjectionFactory =
        RealRailLineProjectionFactory(RealRailLinePathProjectionFactory())
    private val projector: RailMapProjector =
        RealRailMapProjector(RealRailPathSmoother(6), railLineProjectionFactory)

    init {
        pathProjectionFactory.returns(pathProjection)
        lineProjectionUnderTest = RealRailLineProjection(line, pathProjectionFactory)
    }

    @Test
    fun `project uses the injected smoother and line projection seams`() {
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
                    LocationDescription("Approaching Green Park"),
                    LocationEstimate(
                        LocationType.STATION_BOARD,
                        LocationDescription("Green Park Underground Station"),
                        nextStop.coordinate,
                        nextStop
                    ),
                    nextStop,
                    Instant.parse("2026-03-22T00:50:50Z"),
                    Instant.parse("2026-03-22T00:49:20Z"),
                    PredictionCount(1),
                    emptyList()
                )
            )
        )
        seamPathSmoother.returns(smoothedLineMap)
        seamProjection.projectsStation(nextStop.coordinate, GeoCoordinate(51.507100, -0.142500))
        seamProjection.projectsNextStopAnchor(
            TrainId("victoria|257"),
            TrainMapProjection(
                GeoCoordinate(51.507200, -0.142400),
                HeadingDegrees(32.0)
            )
        )
        seamProjectionFactory.returns(LineId("victoria"), seamProjection)

        val projected = seamProjector.project(snapshot, originalLineMap)

        expectThat(seamPathSmoother.requests).hasSize(1)
        expectThat(seamProjectionFactory.requestedLines).hasSize(1)
        expectThat(projected.lines).isEqualTo(smoothedLineMap.lines)
        expectThat(projected.stations).hasSize(1)
        expectThat(projected.stations.first().coordinate).isEqualTo(GeoCoordinate(51.507100, -0.142500))
        expectThat(projected.trains).hasSize(1)
        expectThat(projected.trains.first().coordinate).isEqualTo(GeoCoordinate(51.507200, -0.142400))
        expectThat(projected.trains.first().heading).isEqualTo(HeadingDegrees(32.0))
    }

    @Test
    fun `line projection uses the injected path projection seam`() {
        val stationCoordinate = GeoCoordinate(51.506947, -0.142787)
        val projectedCoordinate = GeoCoordinate(51.507000, -0.142700)
        val stationPathProjection = PathCoordinateProjection(
            pathProjection,
            projectedCoordinate,
            0.01,
            40.0
        )
        pathProjection.projectsCoordinate(stationCoordinate, stationPathProjection)

        val projection = lineProjectionUnderTest.projectStation(stationCoordinate)

        expectThat(pathProjectionFactory.requestedPaths).hasSize(1)
        expectThat(pathProjection.projectCoordinateRequests).hasSize(1)
        expectThat(projection).isEqualTo(projectedCoordinate)
    }

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
    fun `project anchors trains at the next stop when no departure has been observed yet`() {
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
                    Instant.parse("2026-03-22T00:50:50Z"),
                    Instant.parse("2026-03-22T00:49:20Z"),
                    PredictionCount(1),
                    emptyList()
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
                    Instant.parse("2026-03-22T00:50:50Z"),
                    Instant.parse("2026-03-22T00:49:20Z"),
                    PredictionCount(1),
                    emptyList()
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
                    Instant.parse("2026-03-22T00:50:50Z"),
                    Instant.parse("2026-03-22T00:49:20Z"),
                    PredictionCount(1),
                    emptyList()
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
                    Instant.parse("2026-03-22T00:50:50Z"),
                    Instant.parse("2026-03-22T00:49:20Z"),
                    PredictionCount(1),
                    emptyList()
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

        val projected = RealRailMapProjector(
            RealIdentityRailPathSmoother(),
            railLineProjectionFactory
        ).project(snapshot, lineMap)

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
                    Instant.parse("2026-03-22T00:49:50Z"),
                    Instant.parse("2026-03-22T00:49:20Z"),
                    PredictionCount(1),
                    emptyList()
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
                    Instant.parse("2026-03-22T00:50:05Z"),
                    Instant.parse("2026-03-22T00:49:20Z"),
                    PredictionCount(1),
                    emptyList()
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
    fun `projectBetweenStationsAtProgress uses the shortest loop segment when a station appears twice`() {
        val repeatedLoopStation = StationReference(
            StationId("A"),
            StationName("Alpha Underground Station"),
            GeoCoordinate(51.0, -0.4)
        )
        val previousStation = StationReference(
            StationId("D"),
            StationName("Delta Underground Station"),
            GeoCoordinate(51.0, -0.3)
        )
        val lineProjection =
            RealRailLineProjection(
                RailLine(
                    LineId("circle"),
                    LineName("Circle"),
                    listOf(
                        RailLinePath(
                            listOf(
                                GeoCoordinate(51.0, -0.4),
                                GeoCoordinate(51.1, -0.4),
                                GeoCoordinate(51.1, -0.3),
                                GeoCoordinate(51.0, -0.3),
                                GeoCoordinate(51.0, -0.4)
                            )
                        )
                    ),
                    listOf(
                        RailLineSequence(
                            TrainDirection("outbound"),
                            listOf(
                                repeatedLoopStation,
                                StationReference(
                                    StationId("B"),
                                    StationName("Bravo Underground Station"),
                                    GeoCoordinate(51.1, -0.4)
                                ),
                                StationReference(
                                    StationId("C"),
                                    StationName("Charlie Underground Station"),
                                    GeoCoordinate(51.1, -0.3)
                                ),
                                previousStation,
                                repeatedLoopStation
                            )
                        )
                    )
                ),
                RealRailLinePathProjectionFactory()
            )

        val projection =
            lineProjection.projectBetweenStationsAtProgress(previousStation, repeatedLoopStation, 0.5)

        expectThat(projection).isNotNull().get(TrainMapProjection::coordinate).isEqualTo(GeoCoordinate(51.0, -0.35))
    }

    @Test
    fun `headingAtProgress respects reverse travel direction`() {
        val projectedPath = RealRailLinePathProjection(
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
        val projectedPath = RealRailLinePathProjection(
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
