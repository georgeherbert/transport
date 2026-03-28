package transport

interface ServiceResponseMapper {
    fun mapResponse(mapSnapshot: RailMapSnapshot): RailMapSnapshotJson
    fun trainPositionsResponse(trainPositions: RailMapTrainPositions): RailMapTrainPositionsJson
    fun errorResponse(error: TransportError): ApiErrorJson
}

class ServiceResponseMapperHttp : ServiceResponseMapper {
    override fun mapResponse(mapSnapshot: RailMapSnapshot) =
        RailMapSnapshotJson(
            mapSnapshot.source.value,
            mapSnapshot.generatedAt.toString(),
            mapSnapshot.cached,
            mapSnapshot.cacheAge.seconds,
            mapSnapshot.stationsQueried.value,
            mapSnapshot.stationsFailed.value,
            mapSnapshot.partial,
            mapSnapshot.trainCount.value,
            mapSnapshot.lines.map(::lineJson),
            mapSnapshot.stations.map(::mapStationJson),
            mapSnapshot.trains.map(::mapTrainJson)
        )

    override fun trainPositionsResponse(trainPositions: RailMapTrainPositions) =
        RailMapTrainPositionsJson(
            trainPositions.source.value,
            trainPositions.generatedAt.toString(),
            trainPositions.cached,
            trainPositions.cacheAge.seconds,
            trainPositions.stationsQueried.value,
            trainPositions.stationsFailed.value,
            trainPositions.partial,
            trainPositions.trainCount.value,
            trainPositions.trains.map(::mapTrainPositionJson)
        )

    override fun errorResponse(error: TransportError) =
        when (error) {
            is TransportError.MetadataUnavailable ->
                ApiErrorJson("metadata_unavailable", error.message)
            is TransportError.SnapshotUnavailable ->
                ApiErrorJson("snapshot_unavailable", error.message)
            is TransportError.UpstreamHttpFailure ->
                ApiErrorJson(
                    "upstream_http_failure",
                    "TfL returned HTTP ${error.statusCode} for ${error.endpoint}."
                )
            is TransportError.UpstreamNetworkFailure ->
                ApiErrorJson("upstream_network_failure", error.message)
            is TransportError.UpstreamPayloadFailure ->
                ApiErrorJson("upstream_payload_failure", error.message)
        }

    private fun lineJson(line: RailLine) =
        RailLineJson(
            line.id.value,
            line.name.value,
            line.paths.map { path ->
                RailLinePathJson(
                    path.coordinates.map(::geoCoordinateJson)
                )
            }
        )

    private fun mapTrainJson(train: RailMapTrain) =
        RailMapTrainJson(
            train.trainId.value,
            train.vehicleId?.value,
            train.lineId.value,
            train.lineName.value,
            train.direction?.value,
            train.destinationName?.value,
            train.towards?.value,
            train.currentLocation.value,
            train.coordinate?.let(::geoCoordinateJson),
            train.heading?.value,
            train.expectedArrival?.toString(),
            train.observedAt?.toString(),
            train.futureArrivals.map(::futureArrivalJson)
        )

    private fun mapTrainPositionJson(train: RailMapTrain) =
        RailMapTrainPositionJson(
            train.trainId.value,
            train.vehicleId?.value,
            train.lineId.value,
            train.lineName.value,
            train.direction?.value,
            train.destinationName?.value,
            train.towards?.value,
            train.currentLocation.value,
            train.coordinate?.let(::geoCoordinateJson),
            train.heading?.value,
            train.expectedArrival?.toString(),
            train.observedAt?.toString()
        )

    private fun mapStationJson(station: MapStation) =
        MapStationJson(
            station.id.value,
            station.name.value,
            geoCoordinateJson(station.coordinate),
            station.lineIds.map(LineId::value)
        )

    private fun geoCoordinateJson(geoCoordinate: GeoCoordinate) =
        GeoCoordinateJson(geoCoordinate.lat, geoCoordinate.lon)

    private fun futureArrivalJson(arrival: FutureStationArrival) =
        FutureStationArrivalJson(
            arrival.stationId?.value,
            arrival.stationName.value,
            arrival.expectedArrival.toString()
        )
}
