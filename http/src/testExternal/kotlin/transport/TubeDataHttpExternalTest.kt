package transport

import java.net.http.HttpClient
import java.time.Duration
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.isGreaterThan

class TubeDataHttpExternalTest {
    private val tubeData: TubeData =
        TubeDataHttp(
            TflHttpClientConfig(
                "https://api.tfl.gov.uk",
                Duration.ofSeconds(20),
                System.getenv("TFL_SUBSCRIPTION_KEY")
            ),
            HttpClient.newHttpClient(),
            TflPayloadParserHttp(transportJson())
        )

    @Test
    fun `fetchModeStations returns live elizabeth stop points`() {
        runBlocking {
            val result = tubeData.fetchModeStations(TransportModeName("elizabeth-line"))

            expectThat(result).isSuccess().get { size }.isGreaterThan(0)
        }
    }

    @Test
    fun `fetchLineRoutes returns live tram geometry`() {
        runBlocking {
            val result = tubeData.fetchLineRoutes(LineId("tram"))

            expectThat(result).isSuccess().get { paths.size }.isGreaterThan(0)
        }
    }

    @Test
    fun `fetchPredictions returns the live tram feed`() {
        runBlocking {
            val result = tubeData.fetchPredictions(TransportModeName("tram"))

            expectThat(result).isSuccess().get { size }.isGreaterThan(0)
        }
    }
}
