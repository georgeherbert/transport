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
                System.getenv("TFL_APP_ID"),
                System.getenv("TFL_APP_KEY")
            ),
            HttpClient.newHttpClient(),
            TflPayloadParserHttp(transportJson())
        )

    @Test
    fun `fetchLineStations returns live victoria stop points`() {
        runBlocking {
            val result = tubeData.fetchLineStations(LineId("victoria"))

            expectThat(result).isSuccess().get { size }.isGreaterThan(0)
        }
    }

    @Test
    fun `fetchTubePredictions returns the live tube feed`() {
        runBlocking {
            val result = tubeData.fetchTubePredictions()

            expectThat(result).isSuccess()
        }
    }
}
