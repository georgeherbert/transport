package transport

interface TubeData {
    suspend fun fetchLineStations(lineId: LineId): TransportResult<List<TubeStationRecord>>
    suspend fun fetchTubePredictions(): TransportResult<List<TubePredictionRecord>>
}
