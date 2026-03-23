package transport

import java.time.Instant

interface ServiceResponseMapper {
    fun apiDescription(): ApiDescriptionJson
    fun healthResponse(generatedAt: Instant): HealthJson
    fun mapResponse(mapSnapshot: TubeMapSnapshot): TubeMapSnapshotJson
    fun trainPositionsResponse(trainPositions: TubeMapTrainPositions): TubeMapTrainPositionsJson
    fun lineMapResponse(lineMap: TubeLineMap): TubeLineMapJson
    fun snapshotResponse(snapshot: LiveTubeSnapshot): LiveTubeSnapshotJson
    fun errorResponse(error: TransportError): ApiErrorJson
}

class ServiceResponseMapperHttp : ServiceResponseMapper {
    override fun apiDescription() =
        ApiDescriptionJson(
            "london-rail-live-api",
            "Aggregates TfL rail and tram arrival boards into a live train snapshot and projected map.",
            listOf(
                "GET /health",
                "GET /api/rail/map",
                "GET /api/rail/map/stream",
                "GET /api/rail/lines",
                "GET /api/rail/live",
                "GET /api/rail/live?refresh=true",
                "GET /api/tubes/map",
                "GET /api/tubes/map/stream",
                "GET /api/tubes/lines",
                "GET /api/tubes/live",
                "GET /api/tubes/live?refresh=true"
            ),
            listOf(
                "Location text comes directly from TfL prediction data.",
                "Supported modes are Tube, DLR, Elizabeth line, London Overground, and Tram.",
                "Line geometry comes from imported OpenStreetMap rail alignments.",
                "Coordinates are derived from station metadata, imported rail geometry, and domain projection logic, not onboard GPS.",
                "The backend polls TfL on a fixed interval and pushes fresh snapshots and upstream errors to connected UIs."
            )
        )

    override fun healthResponse(generatedAt: Instant) =
        HealthJson("ok", generatedAt.toString())

    override fun mapResponse(mapSnapshot: TubeMapSnapshot) =
        TubeMapSnapshotJson(
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

    override fun trainPositionsResponse(trainPositions: TubeMapTrainPositions) =
        TubeMapTrainPositionsJson(
            trainPositions.source.value,
            trainPositions.generatedAt.toString(),
            trainPositions.cached,
            trainPositions.cacheAge.seconds,
            trainPositions.stationsQueried.value,
            trainPositions.stationsFailed.value,
            trainPositions.partial,
            trainPositions.trainCount.value,
            trainPositions.trains.map(::mapTrainJson)
        )

    override fun lineMapResponse(lineMap: TubeLineMap) =
        TubeLineMapJson(
            lineMap.lines.map(::lineJson)
        )

    override fun snapshotResponse(snapshot: LiveTubeSnapshot) =
        LiveTubeSnapshotJson(
            snapshot.source.value,
            snapshot.generatedAt.toString(),
            snapshot.cached,
            snapshot.cacheAge.seconds,
            snapshot.stationsQueried.value,
            snapshot.stationsFailed.value,
            snapshot.partial,
            snapshot.trainCount.value,
            snapshot.lines.map(LineId::value),
            snapshot.trains.map { train -> trainJson(train) }
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

    private fun lineJson(line: TubeLine) =
        TubeLineJson(
            line.id.value,
            line.name.value,
            line.paths.map { path ->
                TubeLinePathJson(
                    path.coordinates.map(::geoCoordinateJson)
                )
            }
        )

    private fun mapTrainJson(train: TubeMapTrain) =
        TubeMapTrainJson(
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
            train.secondsToNextStop?.seconds?.toInt(),
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

    private fun trainJson(train: LiveTubeTrain) =
        LiveTubeTrainJson(
            train.trainId.value,
            train.vehicleId?.value,
            train.lineIds.map(LineId::value),
            train.lineNames.map(LineName::value),
            train.direction?.value,
            train.destinationName?.value,
            train.towards?.value,
            train.currentLocation.value,
            locationJson(train.location),
            train.nextStop?.let(::stationReferenceJson),
            train.secondsToNextStop?.seconds?.toInt(),
            train.expectedArrival?.toString(),
            train.observedAt?.toString(),
            train.sourcePredictions.value
        )

    private fun locationJson(location: LocationEstimate) =
        LocationEstimateJson(
            locationTypeJson(location.type),
            location.description.value,
            location.coordinate?.let(::geoCoordinateJson),
            location.station?.let(::stationReferenceJson)
        )

    private fun locationTypeJson(locationType: LocationType) =
        when (locationType) {
            LocationType.STATION_BOARD -> LocationTypeJson.STATION_BOARD
            LocationType.UNKNOWN -> LocationTypeJson.UNKNOWN
        }

    private fun stationReferenceJson(stationReference: StationReference) =
        StationReferenceJson(
            stationReference.id.value,
            stationReference.name.value,
            geoCoordinateJson(stationReference.coordinate)
        )

    private fun geoCoordinateJson(geoCoordinate: GeoCoordinate) =
        GeoCoordinateJson(geoCoordinate.lat, geoCoordinate.lon)
}
