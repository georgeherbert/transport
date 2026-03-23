package transport

import kotlin.test.Test
import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isFailure

class TransportServiceConfigTest {
    @Test
    fun `loadTransportServiceConfig reads subscription key environment variable`() {
        val config = loadTransportServiceConfig(
            mapOf(
                "TFL_SUBSCRIPTION_KEY" to "preferred-key"
            )
        )

        expectThat(config.tflSubscriptionKey).isEqualTo("preferred-key")
        expectThat(config.railMapPollInterval.seconds).isEqualTo(5)
    }

    @Test
    fun `loadTransportServiceConfig reads the rail map poll interval environment variable`() {
        val config = loadTransportServiceConfig(
            mapOf(
                "TFL_SUBSCRIPTION_KEY" to "preferred-key",
                "RAIL_MAP_POLL_INTERVAL_SECONDS" to "8"
            )
        )

        expectThat(config.railMapPollInterval.seconds).isEqualTo(8)
    }

    @Test
    fun `loadTransportServiceConfig fails when the subscription key is unset`() {
        expectCatching {
            loadTransportServiceConfig(emptyMap())
        }
            .isFailure()
            .isA<IllegalArgumentException>()
            .get(Throwable::message)
            .isEqualTo("Missing required environment variable TFL_SUBSCRIPTION_KEY.")
    }
}
