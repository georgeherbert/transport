package transport

import java.time.Duration

data class TflHttpClientConfig(
    val baseUrl: String,
    val requestTimeout: Duration,
    val appId: String?,
    val appKey: String?
)
