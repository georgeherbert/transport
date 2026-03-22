package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.flatMap
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TubeDataHttp(
    private val tflHttpClientConfig: TflHttpClientConfig,
    private val httpClient: HttpClient,
    private val tflPayloadParser: TflPayloadParser
) : TubeData {
    override suspend fun fetchLineStations(lineId: LineId) =
        fetchEndpoint("/Line/${lineId.value}/StopPoints", emptyList())
            .flatMap { body ->
                tflPayloadParser.parseLineStations(body, lineId, "/Line/${lineId.value}/StopPoints")
            }

    override suspend fun fetchTubePredictions() =
        fetchEndpoint(
            "/Mode/tube/Arrivals",
            listOf(QueryParameter("count", "-1"))
        )
            .flatMap { body ->
                tflPayloadParser.parsePredictions(body, "/Mode/tube/Arrivals")
            }

    private suspend fun fetchEndpoint(
        endpoint: String,
        queryParameters: List<QueryParameter>
    ): TransportResult<String> =
        withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder(buildUri(endpoint, queryParameters))
                .timeout(tflHttpClientConfig.requestTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", "transport-tube-api/1.0")
                .GET()
                .build()

            try {
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                if (response.statusCode() in 200..299) {
                    Success(response.body())
                } else {
                    Failure(
                        TransportError.UpstreamHttpFailure(
                            endpoint,
                            response.statusCode(),
                            response.body().take(250)
                        )
                    )
                }
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                Failure(TransportError.UpstreamNetworkFailure(endpoint, exception.message ?: "Request interrupted"))
            } catch (exception: IOException) {
                Failure(TransportError.UpstreamNetworkFailure(endpoint, exception.message ?: exception.javaClass.simpleName))
            }
        }

    private fun buildUri(endpoint: String, queryParameters: List<QueryParameter>): URI {
        val queryParts = mutableListOf<String>()
        queryParameters.forEach { queryParameter ->
            queryParts += "${queryParameter.name}=${encodeQueryValue(queryParameter.value)}"
        }
        if (tflHttpClientConfig.appId != null) {
            queryParts += "app_id=${encodeQueryValue(tflHttpClientConfig.appId)}"
        }
        if (tflHttpClientConfig.appKey != null) {
            queryParts += "app_key=${encodeQueryValue(tflHttpClientConfig.appKey)}"
        }

        val query = if (queryParts.isEmpty()) "" else "?${queryParts.joinToString("&")}"
        return URI.create(tflHttpClientConfig.baseUrl.removeSuffix("/") + endpoint + query)
    }

    private fun encodeQueryValue(value: String) =
        URLEncoder.encode(value, StandardCharsets.UTF_8)
}

data class QueryParameter(
    val name: String,
    val value: String
)
