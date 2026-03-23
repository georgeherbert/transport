package transport

interface RailData {
    suspend fun fetchModeStations(mode: TransportModeName): TransportResult<List<RailStationRecord>>
    suspend fun fetchLineRoutes(lineId: LineId): TransportResult<RailLineRouteRecord>
    suspend fun fetchPredictions(mode: TransportModeName): TransportResult<List<RailPredictionRecord>>
}
