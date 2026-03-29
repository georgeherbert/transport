package transport

import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isFailure
import kotlin.test.Test

class TransportServiceConfigTest {
    @Test
    fun `loadTransportServiceConfig reads subscription key environment variable`() {
        val config = loadTransportServiceConfig(
            mapOf(
                EnvironmentVariables.tflSubscriptionKey.value to "preferred-key"
            )
        )

        expectThat(config.tflSubscriptionKey).isEqualTo("preferred-key")
        expectThat(config.host).isEqualTo(ConfigValues.host)
        expectThat(config.port).isEqualTo(ConfigValues.port)
        expectThat(config.railMapPollInterval).isEqualTo(ConfigValues.railMapPollInterval)
        expectThat(config.railSnapshotCacheTtl).isEqualTo(ConfigValues.railSnapshotCacheTtl)
        expectThat(config.requestTimeout).isEqualTo(ConfigValues.tflRequestTimeout)
        expectThat(config.tflBaseUrl).isEqualTo(ConfigValues.tflBaseUrl)
    }

    @Test
    fun `loadTransportServiceConfig ignores unrelated configuration entries`() {
        val config = loadTransportServiceConfig(
            mapOf(
                EnvironmentVariables.tflSubscriptionKey.value to "preferred-key",
                "IGNORED_CONFIG_A" to "127.0.0.1",
                "IGNORED_CONFIG_B" to "9090",
                "IGNORED_CONFIG_C" to "8"
            )
        )

        expectThat(config.host).isEqualTo(ConfigValues.host)
        expectThat(config.port).isEqualTo(ConfigValues.port)
        expectThat(config.railMapPollInterval).isEqualTo(ConfigValues.railMapPollInterval)
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
