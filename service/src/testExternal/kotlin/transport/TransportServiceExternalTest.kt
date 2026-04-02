package transport

import io.ktor.client.HttpClient
import java.time.Clock
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.isGreaterThan
import kotlin.test.AfterTest
import kotlin.test.Test

class TransportServiceExternalTest {
    private val configuration = System.getenv()
    private val transportServiceConfig = loadTransportServiceConfig(configuration)
    private val httpClient: HttpClient = createTflHttpClient(transportServiceConfig.requestTimeout)
    private val snapshotService: RailSnapshotService =
        RailDataHttp(
            transportServiceConfig.toTflHttpClientConfig(),
            httpClient,
            TflPayloadParserHttp(transportJson())
        )
            .let { railData ->
                RealRailSnapshotService(
                    railData,
                    RealRailMetadataRepository(railData),
                    RealRailSnapshotAssembler(RealRailLocationEstimator()),
                    Clock.systemUTC()
                )
            }

    @AfterTest
    fun tearDown() {
        httpClient.close()
    }

    @Test
    fun `live snapshot uses the bulk feed`() {
        runBlocking {
            val result = snapshotService.refreshLiveSnapshot()

            expectThat(result).isSuccess()
        }
    }

    @Test
    fun `live snapshot returns some live services`() {
        runBlocking {
            val result = snapshotService.refreshLiveSnapshot()

            expectThat(result)
                .isSuccess()
                .get { services.size }
                .isGreaterThan(0)
        }
    }
}
