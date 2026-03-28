package transport

import dev.forkhandles.result4k.Failure

class StubTflPayloadParser : TflPayloadParser {
    private var modeStationsPageResult: TransportResult<TflModeStationsPage> =
        Failure(TransportError.UpstreamPayloadFailure("/stub/mode-stations", "No canned mode stations page."))
    private var lineRouteResult: TransportResult<RailLineRouteRecord> =
        Failure(TransportError.UpstreamPayloadFailure("/stub/line-route", "No canned line route."))
    private var predictionsResult: TransportResult<List<RailPredictionRecord>> =
        Failure(TransportError.UpstreamPayloadFailure("/stub/predictions", "No canned predictions."))

    val modeStationsPageRequests = mutableListOf<ParserRequest>()
    val lineRouteRequests = mutableListOf<ParserRequest>()
    val predictionRequests = mutableListOf<ParserRequest>()

    fun returnsModeStationsPage(page: TflModeStationsPage) {
        modeStationsPageResult = dev.forkhandles.result4k.Success(page)
    }

    fun failsModeStationsPage(error: TransportError) {
        modeStationsPageResult = Failure(error)
    }

    fun returnsLineRoute(route: RailLineRouteRecord) {
        lineRouteResult = dev.forkhandles.result4k.Success(route)
    }

    fun failsLineRoute(error: TransportError) {
        lineRouteResult = Failure(error)
    }

    fun returnsPredictions(predictions: List<RailPredictionRecord>) {
        predictionsResult = dev.forkhandles.result4k.Success(predictions)
    }

    fun failsPredictions(error: TransportError) {
        predictionsResult = Failure(error)
    }

    override fun parseModeStationsPage(body: String, endpoint: String) =
        run {
            modeStationsPageRequests += ParserRequest(body, endpoint)
            modeStationsPageResult
        }

    override fun parseLineRoute(body: String, endpoint: String) =
        run {
            lineRouteRequests += ParserRequest(body, endpoint)
            lineRouteResult
        }

    override fun parsePredictions(body: String, endpoint: String) =
        run {
            predictionRequests += ParserRequest(body, endpoint)
            predictionsResult
        }
}

data class ParserRequest(
    val body: String,
    val endpoint: String
)
