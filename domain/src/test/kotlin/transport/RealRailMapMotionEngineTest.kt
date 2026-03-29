package transport

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.isEqualTo
import strikt.assertions.isGreaterThan
import strikt.assertions.isLessThan
import strikt.assertions.isNotNull

class RealRailMapMotionEngineTest {
    private val motionEngine: RailMapMotionEngine =
        RealRailMapMotionEngine(
            RealRailLineProjectionFactory(RealRailLinePathProjectionFactory())
        )

    @Test
    fun `advance uses the observed departure time and TfL arrival time to interpolate between stations`() {
        val firstSnapshot = snapshotFor(
            Instant.parse("2026-03-22T20:50:00Z"),
            "B",
            Instant.parse("2026-03-22T20:51:00Z")
        )
        val secondSnapshot = snapshotFor(
            Instant.parse("2026-03-22T20:51:00Z"),
            "C",
            Instant.parse("2026-03-22T20:52:00Z")
        )

        motionEngine.observe(firstSnapshot)
        motionEngine.observe(secondSnapshot)

        val animated = motionEngine.advance(secondSnapshot, Instant.parse("2026-03-22T20:51:30Z"))

        expectThat(animated.services.single().coordinate).isNotNull().get { lon }.isGreaterThan(-0.251)
        expectThat(animated.services.single().coordinate).isNotNull().get { lon }.isLessThan(-0.249)
    }

    @Test
    fun `advance leaves services anchored at the next stop until a departure is observed`() {
        val snapshot = snapshotFor(
            Instant.parse("2026-03-22T20:50:00Z"),
            "B",
            Instant.parse("2026-03-22T20:51:00Z")
        )

        val observed = motionEngine.observe(snapshot)
        val animated = motionEngine.advance(observed, Instant.parse("2026-03-22T20:50:30Z"))

        expectThat(animated.services.single().coordinate).isEqualTo(GeoCoordinate(51.0, -0.3))
    }

    @Test
    fun `advance clamps the projection at the next stop after the expected arrival time`() {
        val firstSnapshot = snapshotFor(
            Instant.parse("2026-03-22T20:50:00Z"),
            "B",
            Instant.parse("2026-03-22T20:51:00Z")
        )
        val secondSnapshot = snapshotFor(
            Instant.parse("2026-03-22T20:51:00Z"),
            "C",
            Instant.parse("2026-03-22T20:52:00Z")
        )

        motionEngine.observe(firstSnapshot)
        motionEngine.observe(secondSnapshot)

        val animated = motionEngine.advance(secondSnapshot, Instant.parse("2026-03-22T20:53:00Z"))

        expectThat(animated.services.single().coordinate).isEqualTo(GeoCoordinate(51.0, -0.2))
    }

    @Test
    fun `advance leaves services anchored at the next stop when the observed stop jump is not adjacent`() {
        val firstSnapshot = snapshotFor(
            Instant.parse("2026-03-22T20:50:00Z"),
            "B",
            Instant.parse("2026-03-22T20:51:00Z")
        )
        val secondSnapshot = snapshotFor(
            Instant.parse("2026-03-22T20:51:00Z"),
            "D",
            Instant.parse("2026-03-22T20:52:00Z")
        )

        motionEngine.observe(firstSnapshot)
        val observed = motionEngine.observe(secondSnapshot)
        val animated = motionEngine.advance(observed, Instant.parse("2026-03-22T20:51:30Z"))

        expectThat(animated.services.single().coordinate).isEqualTo(GeoCoordinate(51.0, -0.1))
    }

    private fun snapshotFor(
        generatedAt: Instant,
        nextStopId: String,
        expectedArrival: Instant
    ) =
        RailMapSnapshot(
            transportSourceName,
            generatedAt,
            false,
            Duration.ZERO,
            StationQueryCount(1),
            StationFailureCount(0),
            false,
            LiveServiceCount(1),
            listOf(sampleLine()),
            listOf(
                mapStation("B", -0.3),
                mapStation("C", -0.2),
                mapStation("D", -0.1)
            ),
            listOf(
                movingTrain(generatedAt, nextStopId, expectedArrival)
            )
        )

    private fun sampleLine() =
        RailLine(
            LineId("victoria"),
            LineName("Victoria"),
            listOf(
                RailLinePath(
                    listOf(
                        GeoCoordinate(51.0, -0.4),
                        GeoCoordinate(51.0, -0.3),
                        GeoCoordinate(51.0, -0.2),
                        GeoCoordinate(51.0, -0.1)
                    )
                )
            ),
            listOf(
                RailLineSequence(
                    ServiceDirection("outbound"),
                    listOf(
                        stationReference("A", -0.4),
                        stationReference("B", -0.3),
                        stationReference("C", -0.2),
                        stationReference("D", -0.1)
                    )
                )
            )
        )

    private fun mapStation(
        id: String,
        lon: Double
    ) =
        MapStation(
            StationId(id),
            StationName("$id Underground Station"),
            GeoCoordinate(51.0, lon),
            listOf(LineId("victoria")),
            emptyList()
        )

    private fun movingTrain(
        generatedAt: Instant,
        nextStopId: String,
        expectedArrival: Instant
    ) =
        RailMapService(
            ServiceId("train-1"),
            VehicleId("train-1"),
            LineId("victoria"),
            LineName("Victoria"),
            ServiceDirection("outbound"),
            DestinationName("D Underground Station"),
            TowardsDescription("D"),
            LocationDescription("Structured next stop $nextStopId"),
            stationReference(nextStopId, stopLongitude(nextStopId)),
            coordinateAtNextStop(nextStopId),
            HeadingDegrees(90.0),
            expectedArrival,
            generatedAt,
            listOf(
                FutureStationArrival(
                    StationId(nextStopId),
                    StationName("$nextStopId Underground Station"),
                    expectedArrival
                )
            )
        )

    private fun coordinateAtNextStop(nextStopId: String) =
        when (nextStopId) {
            "A" -> GeoCoordinate(51.0, -0.4)
            "B" -> GeoCoordinate(51.0, -0.3)
            "C" -> GeoCoordinate(51.0, -0.2)
            "D" -> GeoCoordinate(51.0, -0.1)
            else -> error("Unsupported stop id $nextStopId")
        }

    private fun stationReference(
        id: String,
        lon: Double
    ) =
        StationReference(
            StationId(id),
            StationName("$id Underground Station"),
            GeoCoordinate(51.0, lon)
        )

    private fun stopLongitude(id: String) =
        when (id) {
            "A" -> -0.4
            "B" -> -0.3
            "C" -> -0.2
            "D" -> -0.1
            else -> error("Unsupported stop id $id")
        }
}
