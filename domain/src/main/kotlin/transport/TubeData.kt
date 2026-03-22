package transport

interface TubeData {
    suspend fun fetchLineStations(lineId: LineId): TransportResult<List<TubeStationRecord>>
    suspend fun fetchLineRoutes(lineId: LineId): TransportResult<TubeLineRouteRecord>
    suspend fun fetchTubePredictions(): TransportResult<List<TubePredictionRecord>>
}
