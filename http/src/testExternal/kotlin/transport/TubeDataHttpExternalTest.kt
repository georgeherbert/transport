package transport

import io.ktor.client.HttpClient
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.isGreaterThan

class TubeDataHttpExternalTest {
    private val subscriptionKey = requiredEnvironmentVariable("TFL_SUBSCRIPTION_KEY")
    private val httpClient: HttpClient = createTflHttpClient(Duration.ofSeconds(20))
    private val tubeData: TubeData =
        TubeDataHttp(
            TflHttpClientConfig(
                "https://api.tfl.gov.uk",
                Duration.ofSeconds(20),
                subscriptionKey
            ),
            httpClient,
            TflPayloadParserHttp(transportJson())
        )

    private fun requiredEnvironmentVariable(name: String) =
        System.getenv().getOrDefault(name, "").ifBlank {
            throw IllegalArgumentException("Missing required environment variable $name.")
        }

    @AfterTest
    fun tearDown() {
        httpClient.close()
    }

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
