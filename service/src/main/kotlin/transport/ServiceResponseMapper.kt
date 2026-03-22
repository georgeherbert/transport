package transport

import java.time.Instant

interface ServiceResponseMapper {
    fun apiDescription(): ApiDescriptionJson
    fun healthResponse(generatedAt: Instant): HealthJson
    fun snapshotResponse(snapshot: LiveTubeSnapshot): LiveTubeSnapshotJson
    fun errorResponse(error: TransportError): ApiErrorJson
}

class ServiceResponseMapperHttp : ServiceResponseMapper {
    override fun apiDescription() =
        ApiDescriptionJson(
            "london-tube-live-api",
            "Aggregates TfL Tube arrival boards into a live train snapshot.",
            listOf("GET /health", "GET /api/tubes/live", "GET /api/tubes/live?refresh=true"),
            listOf(
                "Location text comes directly from TfL prediction data.",
                "Coordinates are derived from station metadata and prediction text, not onboard GPS."
            )
        )

    override fun healthResponse(generatedAt: Instant) =
        HealthJson("ok", generatedAt.toString())

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
            location.station?.let(::stationReferenceJson),
            location.fromStation?.let(::stationReferenceJson),
            location.toStation?.let(::stationReferenceJson)
        )

    private fun locationTypeJson(locationType: LocationType) =
        when (locationType) {
            LocationType.AT_STATION -> LocationTypeJson.AT_STATION
            LocationType.APPROACHING_STATION -> LocationTypeJson.APPROACHING_STATION
            LocationType.BETWEEN_STATIONS -> LocationTypeJson.BETWEEN_STATIONS
            LocationType.DEPARTED_STATION -> LocationTypeJson.DEPARTED_STATION
            LocationType.NEAR_STATION -> LocationTypeJson.NEAR_STATION
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
