package transport

interface TubeData {
    suspend fun fetchModeStations(mode: TransportModeName): TransportResult<List<TubeStationRecord>>
    suspend fun fetchLineRoutes(lineId: LineId): TransportResult<TubeLineRouteRecord>
    suspend fun fetchPredictions(mode: TransportModeName): TransportResult<List<TubePredictionRecord>>
    suspend fun fetchVehiclePredictions(vehicleIds: List<VehicleId>): TransportResult<List<TubePredictionRecord>>
}
