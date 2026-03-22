package transport

class FakeTubeData(
    private val lineStationHandler: suspend (LineId) -> TransportResult<List<TubeStationRecord>>,
    private val lineRouteHandler: suspend (LineId) -> TransportResult<TubeLineRouteRecord>,
    private val tubePredictionHandler: suspend () -> TransportResult<List<TubePredictionRecord>>
) : TubeData {
    override suspend fun fetchLineStations(lineId: LineId) =
        lineStationHandler(lineId)

    override suspend fun fetchLineRoutes(lineId: LineId) =
        lineRouteHandler(lineId)

    override suspend fun fetchTubePredictions() =
        tubePredictionHandler()
}
