package transport

import java.time.Duration

data class TransportServiceConfig(
    val host: String,
    val port: Int,
    val railSnapshotCacheTtl: Duration,
    val railMapPollInterval: Duration,
    val requestTimeout: Duration,
    val tflBaseUrl: String,
    val tflSubscriptionKey: String
)

fun loadTransportServiceConfig(environment: Map<String, String>) =
    TransportServiceConfig(
        environment.nonBlankOrDefault("HOST", "0.0.0.0"),
        environment.intOrDefault("PORT", 8080),
        Duration.ofSeconds(environment.longOrDefault("RAIL_SNAPSHOT_CACHE_TTL_SECONDS", 20L)),
        Duration.ofSeconds(environment.longOrDefault("RAIL_MAP_POLL_INTERVAL_SECONDS", 5L)),
        Duration.ofSeconds(environment.longOrDefault("TFL_REQUEST_TIMEOUT_SECONDS", 10L)),
        environment.nonBlankOrDefault("TFL_BASE_URL", "https://api.tfl.gov.uk"),
        environment.requiredNonBlank("TFL_SUBSCRIPTION_KEY")
    )

fun TransportServiceConfig.toTflHttpClientConfig() =
    TflHttpClientConfig(tflBaseUrl, requestTimeout, tflSubscriptionKey)

private fun Map<String, String>.requiredNonBlank(name: String) =
    getOrDefault(name, "").ifBlank {
        throw IllegalArgumentException("Missing required environment variable $name.")
    }

private fun Map<String, String>.nonBlankOrDefault(name: String, defaultValue: String) =
    getOrDefault(name, defaultValue).ifBlank { defaultValue }

private fun Map<String, String>.intOrDefault(name: String, defaultValue: Int) =
    getOrDefault(name, defaultValue.toString()).toIntOrNull() ?: defaultValue

private fun Map<String, String>.longOrDefault(name: String, defaultValue: Long) =
    getOrDefault(name, defaultValue.toString()).toLongOrNull() ?: defaultValue
