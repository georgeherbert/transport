package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.flatMap
import dev.forkhandles.result4k.map
import java.io.IOException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class RailDataHttp(
    private val tflHttpClientConfig: TflHttpClientConfig,
    private val httpClient: HttpClient,
    private val tflPayloadParser: TflPayloadParser
) : RailData {
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
            val result = sendRequest(endpoint, queryParameters)

            if (!shouldRetry(result, attempt, maxAttempts)) {
                return result
            }

            delay(retryDelayMillis(attempt))
            attempt += 1
        }

        return sendRequest(endpoint, queryParameters)
    }

    private suspend fun sendRequest(
        endpoint: String,
        queryParameters: List<QueryParameter>
    ): TransportResult<String> {
        return try {
            request(endpoint, queryParameters).toTransportResult(endpoint)
        } catch (exception: CancellationException) {
            throw exception
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

    private suspend fun request(
        endpoint: String,
        queryParameters: List<QueryParameter>
    ) =
        httpClient.get(tflHttpClientConfig.baseUrl.removeSuffix("/") + endpoint) {
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, "transport-rail-api/1.0")
            queryParameters.forEach { queryParameter ->
                parameter(queryParameter.name, queryParameter.value)
            }
            parameter("app_key", tflHttpClientConfig.subscriptionKey)
        }

    private suspend fun HttpResponse.toTransportResult(endpoint: String): TransportResult<String> {
        val body = bodyAsText()
        if (status.isSuccess()) {
            return Success(body)
        }

        return Failure(
            TransportError.UpstreamHttpFailure(
                endpoint,
                status.value,
                body.take(250)
            )
        )
    }
}

data class QueryParameter(
    val name: String,
    val value: String
)
