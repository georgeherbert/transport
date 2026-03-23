package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.isA
import strikt.assertions.isEqualTo

@OptIn(ExperimentalCoroutinesApi::class)
class RealRailMapFeedServiceTest {
    @Test
    fun `start polls immediately and serves the cached snapshot`() =
        runTest {
            val clock = MutableTestClock(Instant.parse("2026-03-22T20:50:00Z"))
            val callCount = AtomicInteger(0)
            val railMapFeedService: RailMapFeedService =
                RealRailMapFeedService(
                    StubRailMapService { forceRefresh ->
                        callCount.incrementAndGet()
                        Success(sampleRailMapSnapshot(clock.instant(), forceRefresh))
                    },
                    RealRailMapMotionEngine(),
                    clock,
                    Duration.ofSeconds(20),
                    backgroundScope
                )

            railMapFeedService.start()
            val result = railMapFeedService.getRailMap(false)

            expectThat(callCount.get()).isEqualTo(1)
            expectThat(result).isSuccess().get { cached }.isEqualTo(true)
            expectThat(result).isSuccess().get { generatedAt }.isEqualTo(clock.instant())
        }

    @Test
    fun `failed poll emits an error update and preserves the last cached snapshot`() =
        runTest {
            val clock = MutableTestClock(Instant.parse("2026-03-22T20:50:00Z"))
            val callCount = AtomicInteger(0)
            val railMapFeedService: RailMapFeedService =
                RealRailMapFeedService(
                    StubRailMapService { forceRefresh ->
                        when (callCount.getAndIncrement()) {
                            0 -> Success(sampleRailMapSnapshot(clock.instant(), forceRefresh))
                            else -> Failure(TransportError.UpstreamHttpFailure("/Mode/tube/Arrivals", 503, "down"))
                        }
                    },
                    RealRailMapMotionEngine(),
                    clock,
                    Duration.ofSeconds(20),
                    backgroundScope
                )

            railMapFeedService.start()
            val errorUpdate = async {
                railMapFeedService.updates().first()
            }
            clock.advanceBy(Duration.ofSeconds(20))
            advanceTimeBy(Duration.ofSeconds(20).toMillis())
            advanceUntilIdle()

            val result = railMapFeedService.getRailMap(false)

            expectThat(errorUpdate.await()).isA<RailMapFeedUpdate.ErrorUpdated>()
            expectThat(result).isSuccess().get { cached }.isEqualTo(true)
            expectThat(railMapFeedService.currentError()).isEqualTo(
                TransportError.UpstreamHttpFailure("/Mode/tube/Arrivals", 503, "down")
            )
        }

    @Test
    fun `animation ticks emit train-only updates`() =
        runTest {
            val generatedAt = Instant.parse("2026-03-22T20:50:00Z")
            val clock = MutableTestClock(generatedAt)
            val initialSnapshot = sampleRailMapSnapshot(generatedAt, false)
            val animatedSnapshot = initialSnapshot.copy(
                trains = initialSnapshot.trains.map { train ->
                    train.copy(
                        coordinate = GeoCoordinate(51.507247, -0.141507),
                        heading = HeadingDegrees(45.0)
                    )
                }
            )
            val railMapFeedService: RailMapFeedService =
                RealRailMapFeedService(
                    StubRailMapService { forceRefresh ->
                        Success(sampleRailMapSnapshot(generatedAt, forceRefresh))
                    },
                    StubRailMapMotionEngine(
                        { snapshot -> snapshot },
                        { snapshot, currentTime ->
                            if (currentTime > snapshot.generatedAt) {
                                animatedSnapshot
                            } else {
                                snapshot
                            }
                        }
                    ),
                    clock,
                    Duration.ofSeconds(20),
                    backgroundScope
                )

            val animatedUpdate = async {
                railMapFeedService.updates().drop(1).first()
            }

            railMapFeedService.start()
            clock.advanceBy(Duration.ofMillis(250))
            advanceTimeBy(Duration.ofMillis(250).toMillis())
            advanceUntilIdle()

            val update = animatedUpdate.await()

            expectThat(update)
                .isA<RailMapFeedUpdate.TrainPositionsUpdated>()
                .get(RailMapFeedUpdate.TrainPositionsUpdated::trainPositions)
                .get(RailMapTrainPositions::trains)
                .get(0)
                .get(RailMapTrain::coordinate)
                .isEqualTo(GeoCoordinate(51.507247, -0.141507))
        }

    @Test
    fun `force refresh requests are throttled to the poll interval`() =
        runTest {
            val clock = MutableTestClock(Instant.parse("2026-03-22T20:50:00Z"))
            val callCount = AtomicInteger(0)
            val railMapFeedService: RailMapFeedService =
                RealRailMapFeedService(
                    StubRailMapService { forceRefresh ->
                        callCount.incrementAndGet()
                        Success(sampleRailMapSnapshot(clock.instant(), forceRefresh))
                    },
                    RealRailMapMotionEngine(),
                    clock,
                    Duration.ofSeconds(20),
                    backgroundScope
                )

            railMapFeedService.start()
            railMapFeedService.getRailMap(true)

            expectThat(callCount.get()).isEqualTo(1)

            clock.advanceBy(Duration.ofSeconds(20))
            railMapFeedService.getRailMap(true)

            expectThat(callCount.get()).isEqualTo(2)
        }
    private fun sampleRailMapSnapshot(
        generatedAt: Instant,
        cached: Boolean
    ) =
        RailMapSnapshot(
            transportSourceName,
            generatedAt,
            cached,
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
                    listOf(LineId("victoria"))
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
                    Duration.ofSeconds(90),
                    Instant.parse("2026-03-22T20:51:30Z"),
                    generatedAt
                )
            )
        )

}

private class MutableTestClock(
    initialInstant: Instant
) : Clock() {
    private var instantValue = initialInstant

    override fun instant(): Instant =
        instantValue

    override fun getZone(): ZoneId =
        ZoneId.of("UTC")

    override fun withZone(zone: ZoneId): Clock =
        this

    fun advanceBy(duration: Duration) {
        instantValue = instantValue.plus(duration)
    }
}
