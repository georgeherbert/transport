package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.isA
import strikt.assertions.isEqualTo

class RealTubeSnapshotServiceTest {
    private val tubeNetwork = testTubeNetwork(
        listOf(
            testStation("940GZZLUGPK", "Green Park Underground Station", 51.506947, -0.142787, setOf("victoria"))
        )
    )

    @Test
    fun `getLiveSnapshot returns fresh snapshot then serves cached snapshot`() {
        runBlocking {
            val predictionRequests = AtomicInteger(0)
            val tubeData = FakeTubeData(
                lineStationHandler = { lineId ->
                    Failure(TransportError.MetadataUnavailable(lineId.value))
                },
                lineRouteHandler = { lineId ->
                    Failure(TransportError.MetadataUnavailable(lineId.value))
                },
                predictionHandler = { mode ->
                    predictionRequests.incrementAndGet()
                    samplePredictions(mode)
                }
            )
            val snapshotService = RealTubeSnapshotService(
                tubeData,
                StubTubeMetadataRepository(Success(tubeNetwork)),
                RealTubeSnapshotAssembler(RealTubeLocationEstimator()),
                Clock.fixed(Instant.parse("2026-03-22T00:49:20Z"), ZoneOffset.UTC),
                Duration.ofSeconds(20)
            )

            val first = snapshotService.getLiveSnapshot(false)
            val second = snapshotService.getLiveSnapshot(false)

            expectThat(first).isSuccess().get { cached }.isEqualTo(false)
            expectThat(second).isSuccess().get { cached }.isEqualTo(true)
            expectThat(predictionRequests.get()).isEqualTo(supportedRailModes.size)
        }
    }

    @Test
    fun `getLiveSnapshot falls back to cached snapshot on refresh failure`() {
        runBlocking {
            val shouldFail = AtomicBoolean(false)
            val tubeData = FakeTubeData(
                lineStationHandler = { lineId ->
                    Failure(TransportError.MetadataUnavailable(lineId.value))
                },
                lineRouteHandler = { lineId ->
                    Failure(TransportError.MetadataUnavailable(lineId.value))
                },
                predictionHandler = { mode ->
                    if (shouldFail.get() && mode == TransportModeName("tube")) {
                        Failure(TransportError.UpstreamNetworkFailure("/Mode/${mode.value}/Arrivals", "boom"))
                    } else {
                        samplePredictions(mode)
                    }
                }
            )
            val snapshotService = RealTubeSnapshotService(
                tubeData,
                StubTubeMetadataRepository(Success(tubeNetwork)),
                RealTubeSnapshotAssembler(RealTubeLocationEstimator()),
                Clock.fixed(Instant.parse("2026-03-22T00:49:20Z"), ZoneOffset.UTC),
                Duration.ofSeconds(20)
            )

            expectThat(snapshotService.getLiveSnapshot(false)).isSuccess()
            shouldFail.set(true)

            val refreshed = snapshotService.getLiveSnapshot(true)

            expectThat(refreshed).isSuccess().get { cached }.isEqualTo(true)
        }
    }

    @Test
    fun `getLiveSnapshot returns failure when the bulk tube feed fails`() {
        runBlocking {
            val tubeData = FakeTubeData(
                lineStationHandler = { lineId ->
                    Failure(TransportError.MetadataUnavailable(lineId.value))
                },
                lineRouteHandler = { lineId ->
                    Failure(TransportError.MetadataUnavailable(lineId.value))
                },
                predictionHandler = { mode ->
                    Failure(TransportError.UpstreamNetworkFailure("/Mode/${mode.value}/Arrivals", "boom"))
                }
            )
            val snapshotService = RealTubeSnapshotService(
                tubeData,
                StubTubeMetadataRepository(Success(tubeNetwork)),
                RealTubeSnapshotAssembler(RealTubeLocationEstimator()),
                Clock.fixed(Instant.parse("2026-03-22T00:49:20Z"), ZoneOffset.UTC),
                Duration.ofSeconds(20)
            )

            val result = snapshotService.getLiveSnapshot(false)

            expectThat(result).isFailure().isA<TransportError.SnapshotUnavailable>()
        }
    }

    private fun samplePredictions(mode: TransportModeName): TransportResult<List<TubePredictionRecord>> =
        if (mode == TransportModeName("tube")) {
            Success(
                listOf(
                    TubePredictionRecord(
                        VehicleId("257"),
                        StationId("940GZZLUGPK"),
                        StationName("Green Park Underground Station"),
                        LineId("victoria"),
                        LineName("Victoria"),
                        TrainDirection("outbound"),
                        DestinationName("Walthamstow Central Underground Station"),
                        Instant.parse("2026-03-22T00:49:20Z"),
                        Duration.ofSeconds(90),
                        LocationDescription("Approaching Green Park"),
                        TowardsDescription("Walthamstow Central"),
                        Instant.parse("2026-03-22T00:50:50Z"),
                        TransportModeName("tube")
                    )
                )
            )
        } else {
            Success(emptyList())
        }
}
