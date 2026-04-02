package transport

import java.time.Duration

data class TransportServiceConfig(
    val host: String,
    val port: Int,
    val railMapPollInterval: Duration,
    val requestTimeout: Duration,
    val tflBaseUrl: String,
    val tflSubscriptionKey: String
)

fun loadTransportServiceConfig(environment: Map<String, String>) =
    TransportServiceConfig(
        ConfigValues.host,
        ConfigValues.port,
        ConfigValues.railMapPollInterval,
        ConfigValues.tflRequestTimeout,
        ConfigValues.tflBaseUrl,
        environment.requiredEnvironmentValue(EnvironmentVariables.tflSubscriptionKey)
    )

fun TransportServiceConfig.toTflHttpClientConfig() =
    TflHttpClientConfig(tflBaseUrl, requestTimeout, tflSubscriptionKey)
