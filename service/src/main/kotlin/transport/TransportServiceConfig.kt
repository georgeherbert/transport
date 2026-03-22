package transport

import java.time.Duration

data class TransportServiceConfig(
    val host: String,
    val port: Int,
    val cacheTtl: Duration,
    val requestTimeout: Duration,
    val tflBaseUrl: String,
    val tflSubscriptionKey: String?
)

fun loadTransportServiceConfig(environment: Map<String, String>) =
    TransportServiceConfig(
        environment["HOST"]?.takeIf(String::isNotBlank) ?: "0.0.0.0",
        environment["PORT"]?.toIntOrNull() ?: 8080,
        Duration.ofSeconds(environment["TUBE_CACHE_TTL_SECONDS"]?.toLongOrNull() ?: 20L),
        Duration.ofSeconds(environment["TFL_REQUEST_TIMEOUT_SECONDS"]?.toLongOrNull() ?: 10L),
        environment["TFL_BASE_URL"]?.takeIf(String::isNotBlank) ?: "https://api.tfl.gov.uk",
        loadTflSubscriptionKey(environment)
    )

fun TransportServiceConfig.toTflHttpClientConfig() =
    TflHttpClientConfig(tflBaseUrl, requestTimeout, tflSubscriptionKey)

private fun loadTflSubscriptionKey(environment: Map<String, String>) =
    environment["TFL_SUBSCRIPTION_KEY"]?.takeIf(String::isNotBlank)
        ?: environment["TFL_APP_KEY"]?.takeIf(String::isNotBlank)
