package transport

import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.get
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEqualTo

class RealRailMetadataRepositoryTest {
    private val railData = FakeRailData()
    private val repository = RealRailMetadataRepository(railData)

    @Test
    fun `getRailNetwork merges shared stations across lines`() {
        runBlocking {
            railData.returnsModeStations(
                TransportModeName("tube"),
                listOf(
                    RailStationRecord(
                        StationId("940GZZLUKSX"),
                        StationName("King's Cross St. Pancras Underground Station"),
                        GeoCoordinate(51.530663, -0.123194),
                        LineId("victoria")
                    ),
                    RailStationRecord(
                        StationId("940GZZLUKSX"),
                        StationName("King's Cross St. Pancras Underground Station"),
                        GeoCoordinate(51.530663, -0.123194),
                        LineId("circle")
                    ),
                    RailStationRecord(
                        StationId("940GZZLUGPK"),
                        StationName("Green Park Underground Station"),
                        GeoCoordinate(51.506947, -0.142787),
                        LineId("circle")
                    )
                )
            )

            val result = repository.getRailNetwork()

            expectThat(result).isSuccess()
            expectThat(result).isSuccess().get { stationsById }.hasSize(2)
            expectThat(result)
                .isSuccess()
                .get { stationsById[StationId("940GZZLUKSX")]!!.lineIds }
                .contains(LineId("victoria"), LineId("circle"))
        }
    }

    @Test
    fun `getRailNetwork reuses cached network`() {
        runBlocking {
            railData.returnsModeStations(
                TransportModeName("tube"),
                listOf(
                    RailStationRecord(
                        StationId("940GZZLUGPK"),
                        StationName("Green Park Underground Station"),
                        GeoCoordinate(51.506947, -0.142787),
                        LineId("victoria")
                    )
                )
            )

            expectThat(repository.getRailNetwork()).isSuccess()
            expectThat(repository.getRailNetwork()).isSuccess()

            expectThat(railData.modeStationRequests).hasSize(supportedRailModes.size)
        }
    }

    @Test
    fun `getRailNetwork returns failure when upstream metadata call fails`() {
        runBlocking {
            val failingMode = supportedRailModes.first()
            railData.failsModeStations(
                failingMode,
                TransportError.UpstreamNetworkFailure("/StopPoint/Mode/${failingMode.value}", "boom")
            )

            val result = repository.getRailNetwork()

            expectThat(result).isFailure().isA<TransportError.UpstreamNetworkFailure>()
        }
    }
}
