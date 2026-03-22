package transport

import kotlin.test.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo

class TransportServiceConfigTest {
    @Test
    fun `loadTransportServiceConfig reads subscription key environment variable`() {
        val config = loadTransportServiceConfig(
            mapOf(
                "TFL_SUBSCRIPTION_KEY" to "preferred-key"
            )
        )

        expectThat(config.tflSubscriptionKey).isEqualTo("preferred-key")
    }

    @Test
    fun `loadTransportServiceConfig leaves subscription key empty when unset`() {
        val config = loadTransportServiceConfig(
            emptyMap()
        )

        expectThat(config.tflSubscriptionKey).isEqualTo(null)
    }
}
