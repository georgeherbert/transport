package transport

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.isA
import strikt.assertions.isEqualTo

class RealRailSnapshotServiceTest {
    private val initialInstant = Instant.parse("2026-03-22T00:49:20Z")
    private val clock = FakeClock(initialInstant)
    private val railNetwork = testRailNetwork(
        listOf(
            testStation("940GZZLUGPK", "Green Park Underground Station", 51.506947, -0.142787, setOf("victoria"))
        )
    )
    private val railData = FakeRailData()
    private val railMetadataRepository = StubRailMetadataRepository()
    private val railSnapshotAssembler = StubRailSnapshotAssembler()
    private val snapshotService = RealRailSnapshotService(
        railData,
        railMetadataRepository,
        railSnapshotAssembler,
        clock
    )

    @Test
    fun `refreshLiveSnapshot uses the injected clock for generatedAt on each refresh`() {
        runBlocking {
            railData.returnsPredictions(TransportModeName("tube"), samplePredictions())
            railMetadataRepository.returns(railNetwork)

            val first = snapshotService.refreshLiveSnapshot()
            clock.advanceBy(Duration.ofSeconds(19))
            val refreshed = snapshotService.refreshLiveSnapshot()
            clock.advanceBy(Duration.ofSeconds(2))
            val refreshedAgain = snapshotService.refreshLiveSnapshot()

            expectThat(first).isSuccess().get { generatedAt }.isEqualTo(initialInstant)
            expectThat(refreshed).isSuccess().get { generatedAt }.isEqualTo(initialInstant.plusSeconds(19))
            expectThat(refreshedAgain).isSuccess().get { generatedAt }.isEqualTo(initialInstant.plusSeconds(21))
            expectThat(railSnapshotAssembler.requests.map(AssembleRequest::generatedAt)).isEqualTo(
                listOf(initialInstant, initialInstant.plusSeconds(19), initialInstant.plusSeconds(21))
            )
            expectThat(railData.predictionRequests.size).isEqualTo(supportedRailModes.size * 3)
        }
    }

    @Test
    fun `refreshLiveSnapshot falls back to cached snapshot on refresh failure`() {
        runBlocking {
            railData.thenReturnsPredictions(TransportModeName("tube"), samplePredictions())
            railData.thenFailsPredictions(
                TransportModeName("tube"),
                TransportError.UpstreamNetworkFailure("/Mode/tube/Arrivals", "boom")
            )
            railMetadataRepository.returns(railNetwork)

            expectThat(snapshotService.refreshLiveSnapshot()).isSuccess()

            val refreshed = snapshotService.refreshLiveSnapshot()

            expectThat(refreshed).isSuccess().get { generatedAt }.isEqualTo(initialInstant)
            expectThat(railSnapshotAssembler.requests.size).isEqualTo(1)
        }
    }

    @Test
    fun `refreshLiveSnapshot returns failure when the bulk tube feed fails`() {
        runBlocking {
            railData.failsPredictions(
                TransportModeName("tube"),
                TransportError.UpstreamNetworkFailure("/Mode/tube/Arrivals", "boom")
            )
            railMetadataRepository.returns(railNetwork)

            val result = snapshotService.refreshLiveSnapshot()

            expectThat(result).isFailure().isA<TransportError.SnapshotUnavailable>()
        }
    }

    private fun samplePredictions() =
        listOf(
            RailPredictionRecord(
                VehicleId("257"),
                StationId("940GZZLUGPK"),
                StationName("Green Park Underground Station"),
                LineId("victoria"),
                LineName("Victoria"),
                ServiceDirection("outbound"),
                DestinationName("Walthamstow Central Underground Station"),
                LocationDescription("Approaching Green Park"),
                TowardsDescription("Walthamstow Central"),
                Instant.parse("2026-03-22T00:50:50Z"),
                TransportModeName("tube")
            )
        )
}
