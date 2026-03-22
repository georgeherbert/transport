package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.flatMap
import dev.forkhandles.result4k.map
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TubeDataHttp(
    private val tflHttpClientConfig: TflHttpClientConfig,
    private val httpClient: HttpClient,
    private val tflPayloadParser: TflPayloadParser
) : TubeData {
    override suspend fun fetchModeStations(mode: TransportModeName) =
        fetchModeStationsPage(mode, 1)
            .flatMap { firstPage ->
                val remainingPageNumbers = ((firstPage.page + 1)..lastPage(firstPage)).toList()
                remainingPageNumbers
                    .map { pageNumber -> fetchModeStationsPage(mode, pageNumber) }
                    .failFast()
                    .map { remainingPages ->
                        (listOf(firstPage) + remainingPages)
                            .flatMap(TflModeStationsPage::stations)
                    }
            }

    override suspend fun fetchLineRoutes(lineId: LineId) =
        fetchEndpoint(
            "/Line/${lineId.value}/Route/Sequence/all",
            listOf(QueryParameter("serviceTypes", "Regular"))
        ).flatMap { body ->
            tflPayloadParser.parseLineRoute(body, "/Line/${lineId.value}/Route/Sequence/all")
        }

    override suspend fun fetchPredictions(mode: TransportModeName) =
        fetchEndpoint(
            "/Mode/${mode.value}/Arrivals",
            listOf(QueryParameter("count", "-1"))
        )
            .flatMap { body ->
                tflPayloadParser.parsePredictions(body, "/Mode/${mode.value}/Arrivals")
            }

    private suspend fun fetchModeStationsPage(
        mode: TransportModeName,
        pageNumber: Int
    ) =
        fetchEndpoint(
            "/StopPoint/Mode/${mode.value}",
            listOf(QueryParameter("page", pageNumber.toString()))
        ).flatMap { body ->
            tflPayloadParser.parseModeStationsPage(body, "/StopPoint/Mode/${mode.value}?page=$pageNumber")
        }

    private suspend fun fetchEndpoint(
        endpoint: String,
        queryParameters: List<QueryParameter>
    ): TransportResult<String> {
        val maxAttempts = 3
        var attempt = 1

        while (attempt <= maxAttempts) {
            val result = withContext(Dispatchers.IO) {
                sendRequest(endpoint, queryParameters)
            }

            if (!shouldRetry(result, attempt, maxAttempts)) {
                return result
            }

            delay(retryDelayMillis(attempt))
            attempt += 1
        }

        return withContext(Dispatchers.IO) {
            sendRequest(endpoint, queryParameters)
        }
    }

    private fun sendRequest(
        endpoint: String,
        queryParameters: List<QueryParameter>
    ): TransportResult<String> {
        val request = HttpRequest.newBuilder(buildUri(endpoint, queryParameters))
            .timeout(tflHttpClientConfig.requestTimeout)
            .header("Accept", "application/json")
            .header("User-Agent", "transport-rail-api/1.0")
            .GET()
            .build()

        return try {
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

    private fun shouldRetry(
        result: TransportResult<String>,
        attempt: Int,
        maxAttempts: Int
    ): Boolean {
        if (result !is Failure) {
            return false
        }

        val reason = result.reason
        return reason is TransportError.UpstreamHttpFailure &&
            reason.statusCode == 429 &&
            attempt < maxAttempts
    }

    private fun retryDelayMillis(attempt: Int) =
        when (attempt) {
            1 -> 250L
            2 -> 500L
            else -> 1000L
        }

    private fun lastPage(page: TflModeStationsPage): Int {
        if (page.total <= 0 || page.pageSize <= 0) {
            return page.page
        }

        return ((page.total - 1) / page.pageSize) + 1
    }

    private fun buildUri(endpoint: String, queryParameters: List<QueryParameter>): URI {
        val queryParts = mutableListOf<String>()
        queryParameters.forEach { queryParameter ->
            queryParts += "${queryParameter.name}=${encodeQueryValue(queryParameter.value)}"
        }
        queryParts += "app_key=${encodeQueryValue(tflHttpClientConfig.subscriptionKey)}"

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
