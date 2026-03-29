package transport

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.isA
import strikt.assertions.isEqualTo

class RealRailSnapshotServiceTest {
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
        Clock.fixed(Instant.parse("2026-03-22T00:49:20Z"), ZoneOffset.UTC),
        Duration.ofSeconds(20)
    )

    @Test
    fun `getLiveSnapshot returns fresh snapshot then serves cached snapshot`() {
        runBlocking {
            railData.returnsPredictions(TransportModeName("tube"), samplePredictions())
            railMetadataRepository.returns(railNetwork)

            val first = snapshotService.getLiveSnapshot(false)
            val second = snapshotService.getLiveSnapshot(false)

            expectThat(first).isSuccess()
            expectThat(second).isSuccess()
            expectThat(railSnapshotAssembler.requests.size).isEqualTo(1)
            expectThat(railData.predictionRequests.size).isEqualTo(supportedRailModes.size)
        }
    }

    @Test
    fun `getLiveSnapshot falls back to cached snapshot on refresh failure`() {
        runBlocking {
            railData.thenReturnsPredictions(TransportModeName("tube"), samplePredictions())
            railData.thenFailsPredictions(
                TransportModeName("tube"),
                TransportError.UpstreamNetworkFailure("/Mode/tube/Arrivals", "boom")
            )
            railMetadataRepository.returns(railNetwork)

            expectThat(snapshotService.getLiveSnapshot(false)).isSuccess()

            val refreshed = snapshotService.getLiveSnapshot(true)

            expectThat(refreshed).isSuccess()
            expectThat(railSnapshotAssembler.requests.size).isEqualTo(1)
        }
    }

    @Test
    fun `getLiveSnapshot returns failure when the bulk tube feed fails`() {
        runBlocking {
            railData.failsPredictions(
                TransportModeName("tube"),
                TransportError.UpstreamNetworkFailure("/Mode/tube/Arrivals", "boom")
            )
            railMetadataRepository.returns(railNetwork)

            val result = snapshotService.getLiveSnapshot(false)

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
