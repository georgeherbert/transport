package transport

import java.time.Duration

data class TransportServiceConfig(
    val host: String,
    val port: Int,
    val cacheTtl: Duration,
    val requestTimeout: Duration,
    val tflBaseUrl: String,
    val tflAppId: String?,
    val tflAppKey: String?
)

fun loadTransportServiceConfig(environment: Map<String, String>) =
    TransportServiceConfig(
        environment["HOST"]?.takeIf(String::isNotBlank) ?: "0.0.0.0",
        environment["PORT"]?.toIntOrNull() ?: 8080,
        Duration.ofSeconds(environment["TUBE_CACHE_TTL_SECONDS"]?.toLongOrNull() ?: 20L),
        Duration.ofSeconds(environment["TFL_REQUEST_TIMEOUT_SECONDS"]?.toLongOrNull() ?: 10L),
        environment["TFL_BASE_URL"]?.takeIf(String::isNotBlank) ?: "https://api.tfl.gov.uk",
        environment["TFL_APP_ID"]?.takeIf(String::isNotBlank),
        environment["TFL_APP_KEY"]?.takeIf(String::isNotBlank)
    )

fun TransportServiceConfig.toTflHttpClientConfig() =
    TflHttpClientConfig(tflBaseUrl, requestTimeout, tflAppId, tflAppKey)
