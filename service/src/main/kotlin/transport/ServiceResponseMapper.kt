package transport

interface ServiceResponseMapper {
    fun mapResponse(mapSnapshot: RailMapSnapshot): RailMapSnapshotJson
    fun servicePositionsResponse(servicePositions: RailMapServicePositions): RailMapServicePositionsJson
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
            mapSnapshot.serviceCount.value,
            mapSnapshot.lines.map(::lineJson),
            mapSnapshot.stations.map(::mapStationJson),
            mapSnapshot.services.map(::mapServiceJson)
        )

    override fun servicePositionsResponse(servicePositions: RailMapServicePositions) =
        RailMapServicePositionsJson(
            servicePositions.source.value,
            servicePositions.generatedAt.toString(),
            servicePositions.cached,
            servicePositions.cacheAge.seconds,
            servicePositions.stationsQueried.value,
            servicePositions.stationsFailed.value,
            servicePositions.partial,
            servicePositions.serviceCount.value,
            servicePositions.stations.map(::mapStationJson),
            servicePositions.services.map(::mapServiceJson)
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

    private fun mapServiceJson(service: RailMapService) =
        RailMapServiceJson(
            service.serviceId.value,
            service.vehicleId.value,
            service.lineId.value,
            service.lineName.value,
            service.direction?.value,
            service.destinationName?.value,
            service.towards?.value,
            service.currentLocation.value,
            service.coordinate?.let(::geoCoordinateJson),
            service.heading?.value,
            service.expectedArrival?.toString(),
            service.observedAt?.toString(),
            service.futureArrivals.map(::futureArrivalJson)
        )

    private fun mapStationJson(station: MapStation) =
        MapStationJson(
            station.id.value,
            station.name.value,
            geoCoordinateJson(station.coordinate),
            station.lineIds.map(LineId::value),
            station.arrivals.map(::stationArrivalJson)
        )

    private fun geoCoordinateJson(geoCoordinate: GeoCoordinate) =
        GeoCoordinateJson(geoCoordinate.lat, geoCoordinate.lon)

    private fun futureArrivalJson(arrival: FutureStationArrival) =
        FutureStationArrivalJson(
            arrival.stationId?.value,
            arrival.stationName.value,
            arrival.expectedArrival.toString()
        )

    private fun stationArrivalJson(arrival: StationArrival) =
        StationArrivalJson(
            arrival.serviceId.value,
            arrival.lineId.value,
            arrival.lineName.value,
            arrival.destinationName?.value,
            arrival.expectedArrival.toString()
        )
}
