package transport

import java.time.Duration
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.isEqualTo

class TransportServiceExternalTest {
    private val subscriptionKey = requiredEnvironmentVariable("TFL_SUBSCRIPTION_KEY")
    private val snapshotService: TubeSnapshotService =
        createTubeSnapshotService(
            TransportServiceConfig(
                "127.0.0.1",
                8080,
                Duration.ofSeconds(20),
                Duration.ofSeconds(20),
                "https://api.tfl.gov.uk",
                subscriptionKey
            ),
            transportJson()
        )

    private fun requiredEnvironmentVariable(name: String) =
        System.getenv().getOrDefault(name, "").ifBlank {
            throw IllegalArgumentException("Missing required environment variable $name.")
        }

    @Test
    fun `live snapshot uses the bulk feed without partial station failures`() {
        runBlocking {
            val result = snapshotService.getLiveSnapshot(true)

            expectThat(result).isSuccess().get { partial }.isEqualTo(false)
            expectThat(result).isSuccess().get { stationsFailed }.isEqualTo(StationFailureCount(0))
        }
    }
}
