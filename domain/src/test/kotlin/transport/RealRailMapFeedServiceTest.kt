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
    private val railMapQuery = StubRailMapQuery()
    private val railMapMotionEngine = StubRailMapMotionEngine()

    @Test
    fun `start polls immediately and serves the cached snapshot`() =
        runTest {
            railMapQuery.returns(true, sampleRailMapSnapshot(clock.instant(), true))
            val railMapFeedService = railMapFeedService(backgroundScope)

            railMapFeedService.start()
            val result = railMapFeedService.getRailMap(false)

            expectThat(railMapQuery.refreshRequests.toList()).isEqualTo(listOf(true))
            expectThat(result).isSuccess().get { generatedAt }.isEqualTo(clock.instant())
        }

    @Test
    fun `failed poll emits an error update and preserves the last cached snapshot`() =
        runTest {
            railMapQuery.thenReturns(sampleRailMapSnapshot(clock.instant(), true))
            railMapQuery.thenFailsWith(TransportError.UpstreamHttpFailure("/Mode/tube/Arrivals", 503, "down"))
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
            expectThat(result).isSuccess().get { generatedAt }.isEqualTo(generatedAt)
            expectThat(railMapFeedService.currentError()).isEqualTo(
                TransportError.UpstreamHttpFailure("/Mode/tube/Arrivals", 503, "down")
            )
        }

    @Test
    fun `animation ticks emit service-only updates`() =
        runTest {
            val initialSnapshot = sampleRailMapSnapshot(generatedAt, false)
            val animatedSnapshot = initialSnapshot.copy(
                services = initialSnapshot.services.map { service ->
                    service.copy(
                        coordinate = GeoCoordinate(51.507247, -0.141507),
                        heading = HeadingDegrees(45.0)
                    )
                }
            )
            railMapQuery.returns(true, sampleRailMapSnapshot(generatedAt, true))
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
                .isA<RailMapFeedUpdate.ServicePositionsUpdated>()
                .get(RailMapFeedUpdate.ServicePositionsUpdated::servicePositions)
                .get(RailMapServicePositions::stations)
                .get(0)
                .get(MapStation::arrivals)
                .get(0)
                .get(StationArrival::lineId)
                .isEqualTo(LineId("victoria"))

            expectThat(update)
                .isA<RailMapFeedUpdate.ServicePositionsUpdated>()
                .get(RailMapFeedUpdate.ServicePositionsUpdated::servicePositions)
                .get(RailMapServicePositions::services)
                .get(0)
                .get(RailMapService::coordinate)
                .isEqualTo(GeoCoordinate(51.507247, -0.141507))
        }

    @Test
    fun `force refresh requests are throttled to the poll interval`() =
        runTest {
            railMapQuery.returns(true, sampleRailMapSnapshot(clock.instant(), true))
            val railMapFeedService = railMapFeedService(backgroundScope)

            railMapFeedService.start()
            railMapFeedService.getRailMap(true)

            expectThat(railMapQuery.refreshRequests.toList()).isEqualTo(listOf(true))

            clock.advanceBy(Duration.ofSeconds(20))
            railMapFeedService.getRailMap(true)

            expectThat(railMapQuery.refreshRequests.toList()).isEqualTo(listOf(true, true))
        }

    private fun railMapFeedService(coroutineScope: CoroutineScope): RailMapFeedService =
        RealRailMapFeedService(
            railMapQuery,
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
            generatedAt,
            StationFailureCount(0),
            false,
            LiveServiceCount(1),
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
                    listOf(LineId("victoria")),
                    listOf(
                        StationArrival(
                            ServiceId("257"),
                            LineId("victoria"),
                            DestinationName("Walthamstow Central Underground Station"),
                            Instant.parse("2026-03-22T20:51:30Z")
                        )
                    )
                )
            ),
            listOf(
                RailMapService(
                    ServiceId("257"),
                    LineId("victoria"),
                    LineName("Victoria"),
                    ServiceDirection("outbound"),
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
                    Instant.parse("2026-03-22T20:51:30Z"),
                    listOf(
                        FutureStationArrival(
                            StationId("940GZZLUGPK"),
                            StationName("Green Park Underground Station"),
                            Instant.parse("2026-03-22T20:51:30Z")
                        )
                    )
                )
            )
        )
}
