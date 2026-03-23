package transport

class FakeRailData(
    private val modeStationHandler: suspend (TransportModeName) -> TransportResult<List<RailStationRecord>>,
    private val lineRouteHandler: suspend (LineId) -> TransportResult<RailLineRouteRecord>,
    private val predictionHandler: suspend (TransportModeName) -> TransportResult<List<RailPredictionRecord>>
) : RailData {
    override suspend fun fetchModeStations(mode: TransportModeName) =
        modeStationHandler(mode)

    override suspend fun fetchLineRoutes(lineId: LineId) =
        lineRouteHandler(lineId)

    override suspend fun fetchPredictions(mode: TransportModeName) =
        predictionHandler(mode)
}
