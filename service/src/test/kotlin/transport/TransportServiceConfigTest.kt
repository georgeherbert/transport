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
