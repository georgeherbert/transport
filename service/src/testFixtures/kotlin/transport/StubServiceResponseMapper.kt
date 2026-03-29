package transport

class StubServiceResponseMapper : ServiceResponseMapper {
    private var mapResponseValue: RailMapSnapshotJson? = null
    private var servicePositionsResponseValue: RailMapServicePositionsJson? = null
    private val errorResponses = mutableMapOf<String, ApiErrorJson>()

    val mapRequests = mutableListOf<RailMapSnapshot>()
    val servicePositionsRequests = mutableListOf<RailMapServicePositions>()
    val errorRequests = mutableListOf<TransportError>()

    fun mapsTo(response: RailMapSnapshotJson) {
        mapResponseValue = response
    }

    fun mapsServicePositionsTo(response: RailMapServicePositionsJson) {
        servicePositionsResponseValue = response
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

    override fun servicePositionsResponse(servicePositions: RailMapServicePositions) =
        run {
            servicePositionsRequests += servicePositions
            servicePositionsResponseValue ?: defaultServicePositionsResponse(servicePositions)
        }

    override fun errorResponse(error: TransportError) =
        run {
            errorRequests += error
            errorResponses[errorCode(error)] ?: ApiErrorJson(errorCode(error), errorMessage(error))
        }

    private fun defaultMapResponse(mapSnapshot: RailMapSnapshot) =
        RailMapSnapshotJson(
            mapSnapshot.generatedAt.toString(),
            mapSnapshot.stationsFailed.value,
            mapSnapshot.partial,
            mapSnapshot.serviceCount.value,
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
                    station.lineIds.map(LineId::value),
                    station.arrivals.map(::stationArrivalJson)
                )
            },
            mapSnapshot.services.map(::serviceJson)
        )

    private fun defaultServicePositionsResponse(servicePositions: RailMapServicePositions) =
        RailMapServicePositionsJson(
            servicePositions.generatedAt.toString(),
            servicePositions.stationsFailed.value,
            servicePositions.partial,
            servicePositions.serviceCount.value,
            servicePositions.stations.map { station ->
                MapStationJson(
                    station.id.value,
                    station.name.value,
                    GeoCoordinateJson(station.coordinate.lat, station.coordinate.lon),
                    station.lineIds.map(LineId::value),
                    station.arrivals.map(::stationArrivalJson)
                )
            },
            servicePositions.services.map(::serviceJson)
        )

    private fun serviceJson(service: RailMapService) =
        RailMapServiceJson(
            service.serviceId.value,
            service.lineId.value,
            service.lineName.value,
            service.destinationName?.value,
            service.towards?.value,
            service.currentLocation.value,
            service.coordinate?.let { coordinate ->
                GeoCoordinateJson(coordinate.lat, coordinate.lon)
            },
            service.heading?.value,
            service.futureArrivals.map(::futureArrivalJson)
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

    private fun stationArrivalJson(arrival: StationArrival) =
        StationArrivalJson(
            arrival.serviceId.value,
            arrival.lineId.value,
            arrival.destinationName?.value,
            arrival.expectedArrival.toString()
        )
}
