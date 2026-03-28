package transport

class StubServiceResponseMapper : ServiceResponseMapper {
    private var mapResponseValue: RailMapSnapshotJson? = null
    private var trainPositionsResponseValue: RailMapTrainPositionsJson? = null
    private val errorResponses = mutableMapOf<String, ApiErrorJson>()

    val mapRequests = mutableListOf<RailMapSnapshot>()
    val trainPositionsRequests = mutableListOf<RailMapTrainPositions>()
    val errorRequests = mutableListOf<TransportError>()

    fun mapsTo(response: RailMapSnapshotJson) {
        mapResponseValue = response
    }

    fun mapsTrainPositionsTo(response: RailMapTrainPositionsJson) {
        trainPositionsResponseValue = response
    }

    fun mapsError(
        errorCode: String,
        response: ApiErrorJson
    ) {
        errorResponses[errorCode] = response
    }

    override fun mapResponse(mapSnapshot: RailMapSnapshot) =
        run {
            mapRequests += mapSnapshot
            mapResponseValue ?: defaultMapResponse(mapSnapshot)
        }

    override fun trainPositionsResponse(trainPositions: RailMapTrainPositions) =
        run {
            trainPositionsRequests += trainPositions
            trainPositionsResponseValue ?: defaultTrainPositionsResponse(trainPositions)
        }

    override fun errorResponse(error: TransportError) =
        run {
            errorRequests += error
            errorResponses[errorCode(error)] ?: ApiErrorJson(errorCode(error), errorMessage(error))
        }

    private fun defaultMapResponse(mapSnapshot: RailMapSnapshot) =
        RailMapSnapshotJson(
            mapSnapshot.source.value,
            mapSnapshot.generatedAt.toString(),
            mapSnapshot.cached,
            mapSnapshot.cacheAge.seconds,
            mapSnapshot.stationsQueried.value,
            mapSnapshot.stationsFailed.value,
            mapSnapshot.partial,
            mapSnapshot.trainCount.value,
            mapSnapshot.lines.map { line ->
                RailLineJson(
                    line.id.value,
                    line.name.value,
                    line.paths.map { path ->
                        RailLinePathJson(
                            path.coordinates.map { coordinate ->
                                GeoCoordinateJson(coordinate.lat, coordinate.lon)
                            }
                        )
                    }
                )
            },
            mapSnapshot.stations.map { station ->
                MapStationJson(
                    station.id.value,
                    station.name.value,
                    GeoCoordinateJson(station.coordinate.lat, station.coordinate.lon),
                    station.lineIds.map(LineId::value)
                )
            },
            mapSnapshot.trains.map(::trainJson)
        )

    private fun defaultTrainPositionsResponse(trainPositions: RailMapTrainPositions) =
        RailMapTrainPositionsJson(
            trainPositions.source.value,
            trainPositions.generatedAt.toString(),
            trainPositions.cached,
            trainPositions.cacheAge.seconds,
            trainPositions.stationsQueried.value,
            trainPositions.stationsFailed.value,
            trainPositions.partial,
            trainPositions.trainCount.value,
            trainPositions.trains.map(::trainPositionJson)
        )

    private fun trainJson(train: RailMapTrain) =
        RailMapTrainJson(
            train.trainId.value,
            train.vehicleId?.value,
            train.lineId.value,
            train.lineName.value,
            train.direction?.value,
            train.destinationName?.value,
            train.towards?.value,
            train.currentLocation.value,
            train.coordinate?.let { coordinate ->
                GeoCoordinateJson(coordinate.lat, coordinate.lon)
            },
            train.heading?.value,
            train.expectedArrival?.toString(),
            train.observedAt?.toString(),
            train.futureArrivals.map(::futureArrivalJson)
        )

    private fun trainPositionJson(train: RailMapTrain) =
        RailMapTrainPositionJson(
            train.trainId.value,
            train.vehicleId?.value,
            train.lineId.value,
            train.lineName.value,
            train.direction?.value,
            train.destinationName?.value,
            train.towards?.value,
            train.currentLocation.value,
            train.coordinate?.let { coordinate ->
                GeoCoordinateJson(coordinate.lat, coordinate.lon)
            },
            train.heading?.value,
            train.expectedArrival?.toString(),
            train.observedAt?.toString()
        )

    private fun errorCode(error: TransportError) =
        when (error) {
            is TransportError.MetadataUnavailable -> "metadata_unavailable"
            is TransportError.SnapshotUnavailable -> "snapshot_unavailable"
            is TransportError.UpstreamHttpFailure -> "upstream_http_failure"
            is TransportError.UpstreamNetworkFailure -> "upstream_network_failure"
            is TransportError.UpstreamPayloadFailure -> "upstream_payload_failure"
        }

    private fun errorMessage(error: TransportError) =
        when (error) {
            is TransportError.MetadataUnavailable -> error.message
            is TransportError.SnapshotUnavailable -> error.message
            is TransportError.UpstreamHttpFailure -> error.responseBody
            is TransportError.UpstreamNetworkFailure -> error.message
            is TransportError.UpstreamPayloadFailure -> error.message
        }

    private fun futureArrivalJson(arrival: FutureStationArrival) =
        FutureStationArrivalJson(
            arrival.stationId?.value,
            arrival.stationName.value,
            arrival.expectedArrival.toString()
        )
}
