package transport

class FakeTubeData(
    private val modeStationHandler: suspend (TransportModeName) -> TransportResult<List<TubeStationRecord>>,
    private val lineRouteHandler: suspend (LineId) -> TransportResult<TubeLineRouteRecord>,
    private val predictionHandler: suspend (TransportModeName) -> TransportResult<List<TubePredictionRecord>>,
    private val vehiclePredictionHandler: suspend (List<VehicleId>) -> TransportResult<List<TubePredictionRecord>>
) : TubeData {
    override suspend fun fetchModeStations(mode: TransportModeName) =
        modeStationHandler(mode)

    override suspend fun fetchLineRoutes(lineId: LineId) =
        lineRouteHandler(lineId)

    override suspend fun fetchPredictions(mode: TransportModeName) =
        predictionHandler(mode)

    override suspend fun fetchVehiclePredictions(vehicleIds: List<VehicleId>) =
        vehiclePredictionHandler(vehicleIds)
}
