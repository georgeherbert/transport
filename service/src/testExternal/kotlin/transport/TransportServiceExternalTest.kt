package transport

import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isGreaterThan
import kotlin.test.Test

class TransportServiceExternalTest {
    private val configuration = System.getenv()
    private val snapshotService: RailSnapshotService =
        createRailSnapshotService(
            loadTransportServiceConfig(configuration),
            transportJson()
        )

    @Test
    fun `live snapshot uses the bulk feed without partial station failures`() {
        runBlocking {
            val result = snapshotService.getLiveSnapshot(true)

            expectThat(result).isSuccess().get { partial }.isEqualTo(false)
            expectThat(result).isSuccess().get { stationsFailed }.isEqualTo(StationFailureCount(0))
        }
    }

    @Test
    fun `live snapshot returns some live services`() {
        runBlocking {
            val result = snapshotService.getLiveSnapshot(true)

            expectThat(result)
                .isSuccess()
                .get { services.size }
                .isGreaterThan(0)
        }
    }
}
