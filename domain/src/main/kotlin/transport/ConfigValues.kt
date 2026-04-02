package transport

import java.time.Duration

object ConfigValues {
    const val host = "0.0.0.0"
    const val port = 8080
    val railMapPollInterval: Duration = Duration.ofSeconds(5)
    val tflRequestTimeout: Duration = Duration.ofSeconds(10)
    const val tflBaseUrl = "https://api.tfl.gov.uk"
}
