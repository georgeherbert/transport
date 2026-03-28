package transport

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
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
    private val generatedAt = Instant.parse("2026-03-22T20:50:00Z")
    private val clock = FakeClock(generatedAt)
    private val railMapService = StubRailMapService()
    private val railMapMotionEngine = StubRailMapMotionEngine()

    @Test
    fun `start polls immediately and serves the cached snapshot`() =
        runTest {
            railMapService.returns(true, sampleRailMapSnapshot(clock.instant(), true))
            val railMapFeedService = railMapFeedService(backgroundScope)

            railMapFeedService.start()
            val result = railMapFeedService.getRailMap(false)

            expectThat(railMapService.refreshRequests.toList()).isEqualTo(listOf(true))
            expectThat(result).isSuccess().get { cached }.isEqualTo(true)
            expectThat(result).isSuccess().get { generatedAt }.isEqualTo(clock.instant())
        }

    @Test
    fun `failed poll emits an error update and preserves the last cached snapshot`() =
        runTest {
            railMapService.thenReturns(sampleRailMapSnapshot(clock.instant(), true))
            railMapService.thenFailsWith(TransportError.UpstreamHttpFailure("/Mode/tube/Arrivals", 503, "down"))
            val railMapFeedService = railMapFeedService(backgroundScope)

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
            val initialSnapshot = sampleRailMapSnapshot(generatedAt, false)
            val animatedSnapshot = initialSnapshot.copy(
                trains = initialSnapshot.trains.map { train ->
                    train.copy(
                        coordinate = GeoCoordinate(51.507247, -0.141507),
                        heading = HeadingDegrees(45.0)
                    )
                }
            )
            railMapService.returns(true, sampleRailMapSnapshot(generatedAt, true))
            railMapMotionEngine.advanceReturnsAfter(generatedAt, animatedSnapshot)
            val railMapFeedService = railMapFeedService(backgroundScope)

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
            railMapService.returns(true, sampleRailMapSnapshot(clock.instant(), true))
            val railMapFeedService = railMapFeedService(backgroundScope)

            railMapFeedService.start()
            railMapFeedService.getRailMap(true)

            expectThat(railMapService.refreshRequests.toList()).isEqualTo(listOf(true))

            clock.advanceBy(Duration.ofSeconds(20))
            railMapFeedService.getRailMap(true)

            expectThat(railMapService.refreshRequests.toList()).isEqualTo(listOf(true, true))
        }

    private fun railMapFeedService(coroutineScope: CoroutineScope): RailMapFeedService =
        RealRailMapFeedService(
            railMapService,
            railMapMotionEngine,
            clock,
            Duration.ofSeconds(20),
            coroutineScope
        )

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
