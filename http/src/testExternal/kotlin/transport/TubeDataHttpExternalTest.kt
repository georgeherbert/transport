package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import io.ktor.client.HttpClient
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.isEqualTo
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

    @Test
    fun `fetchVehiclePredictions returns live tube timings when vehicle ids are available`() {
        runBlocking {
            val bulkPredictionResult = tubeData.fetchPredictions(TransportModeName("tube"))
            expectThat(bulkPredictionResult).isSuccess()

            val vehicleIds = when (bulkPredictionResult) {
                is Success ->
                    bulkPredictionResult.value
                        .mapNotNull { prediction -> prediction.vehicleId }
                        .distinct()
                        .take(3)
                is Failure ->
                    error("Expected the live tube feed to succeed.")
            }

            val vehiclePredictionResult = tubeData.fetchVehiclePredictions(vehicleIds)
            expectThat(vehiclePredictionResult).isSuccess()

            val timedPredictionCount = when (vehiclePredictionResult) {
                is Success ->
                    vehiclePredictionResult.value.count { prediction -> prediction.secondsToNextStop != null }
                is Failure ->
                    error("Expected live vehicle predictions to succeed.")
            }

            expectThat(vehicleIds.isEmpty()).isEqualTo(false)
            expectThat(timedPredictionCount).isGreaterThan(0)
        }
    }
}
