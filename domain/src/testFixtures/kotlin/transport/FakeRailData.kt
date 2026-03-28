package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success

class FakeRailData : RailData {
    private val modeStationResults = mutableMapOf<TransportModeName, TransportResult<List<RailStationRecord>>>()
    private val lineRouteResults = mutableMapOf<LineId, TransportResult<RailLineRouteRecord>>()
    private val predictionResults = mutableMapOf<TransportModeName, TransportResult<List<RailPredictionRecord>>>()
    private val queuedPredictionResults = mutableMapOf<TransportModeName, MutableList<TransportResult<List<RailPredictionRecord>>>>()

    val modeStationRequests = mutableListOf<TransportModeName>()
    val lineRouteRequests = mutableListOf<LineId>()
    val predictionRequests = mutableListOf<TransportModeName>()

    fun returnsModeStations(
        mode: TransportModeName,
        stations: List<RailStationRecord>
    ) {
        modeStationResults[mode] = Success(stations)
    }

    fun failsModeStations(
        mode: TransportModeName,
        error: TransportError
    ) {
        modeStationResults[mode] = Failure(error)
    }

    fun returnsLineRoute(
        lineId: LineId,
        route: RailLineRouteRecord
    ) {
        lineRouteResults[lineId] = Success(route)
    }

    fun failsLineRoute(
        lineId: LineId,
        error: TransportError
    ) {
        lineRouteResults[lineId] = Failure(error)
    }

    fun returnsPredictions(
        mode: TransportModeName,
        predictions: List<RailPredictionRecord>
    ) {
        predictionResults[mode] = Success(predictions)
    }

    fun failsPredictions(
        mode: TransportModeName,
        error: TransportError
    ) {
        predictionResults[mode] = Failure(error)
    }

    fun thenReturnsPredictions(
        mode: TransportModeName,
        predictions: List<RailPredictionRecord>
    ) {
        queuedPredictionResults.getOrPut(mode) {
            mutableListOf()
        } += Success(predictions)
    }

    fun thenFailsPredictions(
        mode: TransportModeName,
        error: TransportError
    ) {
        queuedPredictionResults.getOrPut(mode) {
            mutableListOf()
        } += Failure(error)
    }

    override suspend fun fetchModeStations(mode: TransportModeName) =
        run {
            modeStationRequests += mode
            modeStationResults[mode] ?: Success(emptyList())
        }

    override suspend fun fetchLineRoutes(lineId: LineId) =
        run {
            lineRouteRequests += lineId
            lineRouteResults[lineId]
                ?: Success(
                    RailLineRouteRecord(
                        lineId,
                        LineName(lineId.value.replaceFirstChar(Char::titlecase)),
                        emptyList(),
                        emptyList()
                    )
                )
        }

    override suspend fun fetchPredictions(mode: TransportModeName) =
        run {
            predictionRequests += mode
            queuedPredictionResults[mode]
                ?.takeIf { queuedResults -> queuedResults.isNotEmpty() }
                ?.let(::consumeQueuedResult)
                ?: predictionResults[mode]
                ?: Success(emptyList())
        }

    private fun consumeQueuedResult(queue: MutableList<TransportResult<List<RailPredictionRecord>>>) =
        if (queue.size == 1) {
            queue.first()
        } else {
            queue.removeAt(0)
        }
}
