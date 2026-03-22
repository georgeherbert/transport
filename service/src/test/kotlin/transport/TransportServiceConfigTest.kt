package transport

import kotlin.test.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo

class TransportServiceConfigTest {
    @Test
    fun `loadTransportServiceConfig prefers subscription key environment variable`() {
        val config = loadTransportServiceConfig(
            mapOf(
                "TFL_SUBSCRIPTION_KEY" to "preferred-key",
                "TFL_APP_KEY" to "legacy-key"
            )
        )

        expectThat(config.tflSubscriptionKey).isEqualTo("preferred-key")
    }

    @Test
    fun `loadTransportServiceConfig falls back to legacy app key environment variable`() {
        val config = loadTransportServiceConfig(
            mapOf(
                "TFL_APP_KEY" to "legacy-key"
            )
        )

        expectThat(config.tflSubscriptionKey).isEqualTo("legacy-key")
    }
}
