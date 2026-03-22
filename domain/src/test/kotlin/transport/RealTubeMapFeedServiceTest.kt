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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.isA
import strikt.assertions.isEqualTo

@OptIn(ExperimentalCoroutinesApi::class)
class RealTubeMapFeedServiceTest {
    @Test
    fun `start polls immediately and serves the cached snapshot`() =
        runTest {
            val clock = MutableTestClock(Instant.parse("2026-03-22T20:50:00Z"))
            val callCount = AtomicInteger(0)
            val tubeMapFeedService: TubeMapFeedService =
                RealTubeMapFeedService(
                    StubTubeMapService { forceRefresh ->
                        callCount.incrementAndGet()
                        Success(sampleTubeMapSnapshot(clock.instant(), forceRefresh))
                    },
                    RealTubeMapMotionEngine(),
                    clock,
                    Duration.ofSeconds(20),
                    backgroundScope
                )

            tubeMapFeedService.start()
            val result = tubeMapFeedService.getTubeMap(false)

            expectThat(callCount.get()).isEqualTo(1)
            expectThat(result).isSuccess().get { cached }.isEqualTo(true)
            expectThat(result).isSuccess().get { generatedAt }.isEqualTo(clock.instant())
        }

    @Test
    fun `failed poll emits an error update and preserves the last cached snapshot`() =
        runTest {
            val clock = MutableTestClock(Instant.parse("2026-03-22T20:50:00Z"))
            val callCount = AtomicInteger(0)
            val tubeMapFeedService: TubeMapFeedService =
                RealTubeMapFeedService(
                    StubTubeMapService { forceRefresh ->
                        when (callCount.getAndIncrement()) {
                            0 -> Success(sampleTubeMapSnapshot(clock.instant(), forceRefresh))
                            else -> Failure(TransportError.UpstreamHttpFailure("/Mode/tube/Arrivals", 503, "down"))
                        }
                    },
                    RealTubeMapMotionEngine(),
                    clock,
                    Duration.ofSeconds(20),
                    backgroundScope
                )

            tubeMapFeedService.start()
            val errorUpdate = async {
                tubeMapFeedService.updates().first()
            }
            clock.advanceBy(Duration.ofSeconds(20))
            advanceTimeBy(Duration.ofSeconds(20).toMillis())
            advanceUntilIdle()

            val result = tubeMapFeedService.getTubeMap(false)

            expectThat(errorUpdate.await()).isA<TubeMapFeedUpdate.ErrorUpdated>()
            expectThat(result).isSuccess().get { cached }.isEqualTo(true)
            expectThat(tubeMapFeedService.currentError()).isEqualTo(
                TransportError.UpstreamHttpFailure("/Mode/tube/Arrivals", 503, "down")
            )
        }

    @Test
    fun `force refresh requests are throttled to the poll interval`() =
        runTest {
            val clock = MutableTestClock(Instant.parse("2026-03-22T20:50:00Z"))
            val callCount = AtomicInteger(0)
            val tubeMapFeedService: TubeMapFeedService =
                RealTubeMapFeedService(
                    StubTubeMapService { forceRefresh ->
                        callCount.incrementAndGet()
                        Success(sampleTubeMapSnapshot(clock.instant(), forceRefresh))
                    },
                    RealTubeMapMotionEngine(),
                    clock,
                    Duration.ofSeconds(20),
                    backgroundScope
                )

            tubeMapFeedService.start()
            tubeMapFeedService.getTubeMap(true)

            expectThat(callCount.get()).isEqualTo(1)

            clock.advanceBy(Duration.ofSeconds(20))
            tubeMapFeedService.getTubeMap(true)

            expectThat(callCount.get()).isEqualTo(2)
        }
    private fun sampleTubeMapSnapshot(
        generatedAt: Instant,
        cached: Boolean
    ) =
        TubeMapSnapshot(
            transportSourceName,
            generatedAt,
            cached,
            Duration.ZERO,
            StationQueryCount(1),
            StationFailureCount(0),
            false,
            LiveTrainCount(1),
            listOf(
                TubeLine(
                    LineId("victoria"),
                    LineName("Victoria"),
                    listOf(
                        TubeLinePath(
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
                TubeMapTrain(
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
