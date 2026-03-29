package transport

import io.ktor.client.*
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.isGreaterThan
import kotlin.test.AfterTest
import kotlin.test.Test

class RailDataHttpExternalTest {
    private val subscriptionKey = System.getenv()
        .requiredEnvironmentValue(EnvironmentVariables.tflSubscriptionKey)
    private val httpClient: HttpClient = createTflHttpClient(ConfigValues.tflRequestTimeout)
    private val railData: RailData =
        RailDataHttp(
            TflHttpClientConfig(
                ConfigValues.tflBaseUrl,
                ConfigValues.tflRequestTimeout,
                subscriptionKey
            ),
            httpClient,
            TflPayloadParserHttp(transportJson())
        )

    @AfterTest
    fun tearDown() {
        httpClient.close()
    }

    @Test
    fun `fetchModeStations returns live elizabeth stop points`() {
        runBlocking {
            val result = railData.fetchModeStations(TransportModeName("elizabeth-line"))

            expectThat(result).isSuccess().get { size }.isGreaterThan(0)
        }
    }

    @Test
    fun `fetchLineRoutes returns live tram geometry`() {
        runBlocking {
            val result = railData.fetchLineRoutes(LineId("tram"))

            expectThat(result).isSuccess().get { paths.size }.isGreaterThan(0)
        }
    }

    @Test
    fun `fetchPredictions returns the live tram feed`() {
        runBlocking {
            val result = railData.fetchPredictions(TransportModeName("tram"))

            expectThat(result).isSuccess().get { size }.isGreaterThan(0)
        }
    }
}
