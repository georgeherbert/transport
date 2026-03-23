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
    private val motionEngine: RailMapMotionEngine = RealRailMapMotionEngine()

    @Test
    fun `observe learns segment duration from next stop transitions and advances later trains`() {
        val firstSnapshot = snapshotFor(Instant.parse("2026-03-22T20:50:00Z"), "B", "B")
        val secondSnapshot = snapshotFor(Instant.parse("2026-03-22T20:51:00Z"), "C", "B")
        val thirdSnapshot = snapshotFor(Instant.parse("2026-03-22T20:52:00Z"), "D", "C")

        motionEngine.observe(firstSnapshot)
        motionEngine.observe(secondSnapshot)
        motionEngine.observe(thirdSnapshot)

        val animated = motionEngine.advance(thirdSnapshot, Instant.parse("2026-03-22T20:52:30Z"))

        expectThat(animated.trains.last().coordinate).isNotNull().get { lon }.isGreaterThan(-0.251)
        expectThat(animated.trains.last().coordinate).isNotNull().get { lon }.isLessThan(-0.249)
    }

    @Test
    fun `advance leaves trains static when no learned segment duration exists`() {
        val firstSnapshot = snapshotFor(Instant.parse("2026-03-22T20:50:00Z"), "B", "B")
        val secondSnapshot = snapshotFor(Instant.parse("2026-03-22T20:51:00Z"), "C", "B")

        motionEngine.observe(firstSnapshot)
        val observed = motionEngine.observe(secondSnapshot)
        val animated = motionEngine.advance(observed, Instant.parse("2026-03-22T20:51:30Z"))

        expectThat(animated.trains.last().coordinate).isEqualTo(observed.trains.last().coordinate)
    }

    private fun snapshotFor(
        generatedAt: Instant,
        trainOneNextStopId: String,
        trainTwoNextStopId: String
    ) =
        RailMapSnapshot(
            transportSourceName,
            generatedAt,
            false,
            Duration.ZERO,
            StationQueryCount(1),
            StationFailureCount(0),
            false,
            LiveTrainCount(2),
            listOf(
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
                            TrainDirection("outbound"),
                            listOf(
                                stationReference("A", -0.4),
                                stationReference("B", -0.3),
                                stationReference("C", -0.2),
                                stationReference("D", -0.1)
                            )
                        )
                    )
                )
            ),
            listOf(
                MapStation(
                    StationId("B"),
                    StationName("B Underground Station"),
                    GeoCoordinate(51.0, -0.3),
                    listOf(LineId("victoria"))
                ),
                MapStation(
                    StationId("C"),
                    StationName("C Underground Station"),
                    GeoCoordinate(51.0, -0.2),
                    listOf(LineId("victoria"))
                ),
                MapStation(
                    StationId("D"),
                    StationName("D Underground Station"),
                    GeoCoordinate(51.0, -0.1),
                    listOf(LineId("victoria"))
                )
            ),
            listOf(
                movingTrain("train-1", trainOneNextStopId, generatedAt),
                movingTrain("train-2", trainTwoNextStopId, generatedAt)
            )
        )

    private fun movingTrain(
        suffix: String,
        nextStopId: String,
        generatedAt: Instant
    ) =
        RailMapTrain(
            TrainId("victoria|$suffix"),
            VehicleId(suffix),
            LineId("victoria"),
            LineName("Victoria"),
            TrainDirection("outbound"),
            DestinationName("Delta Underground Station"),
            TowardsDescription("Delta"),
            LocationDescription("Structured next stop $nextStopId"),
            stationReference(nextStopId, stopLongitude(nextStopId)),
            coordinateAtNextStop(nextStopId),
            HeadingDegrees(90.0),
            null,
            generatedAt.plusSeconds(60),
            generatedAt
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
