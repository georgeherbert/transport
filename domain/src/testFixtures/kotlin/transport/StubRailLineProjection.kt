package transport

class StubRailLineProjection(
    override val line: RailLine
) : RailLineProjection {
    private var defaultStationProjection: GeoCoordinate? = null
    private var defaultBetweenStationsProjection: ServiceMapProjection? = null
    private var defaultNextStopAnchorProjection: ServiceMapProjection? = null
    private val stationProjections = mutableMapOf<GeoCoordinate, GeoCoordinate?>()
    private val betweenStationProjections = mutableMapOf<SegmentProgressKey, ServiceMapProjection?>()
    private val nextStopAnchorProjections = mutableMapOf<ServiceId, ServiceMapProjection?>()

    val stationRequests = mutableListOf<GeoCoordinate>()
    val betweenStationRequests = mutableListOf<BetweenStationsRequest>()
    val nextStopAnchorRequests = mutableListOf<LiveRailService>()

    fun projectsStationAs(projectedCoordinate: GeoCoordinate?) {
        defaultStationProjection = projectedCoordinate
    }

    fun projectsStation(
        stationCoordinate: GeoCoordinate,
        projectedCoordinate: GeoCoordinate?
    ) {
        stationProjections[stationCoordinate] = projectedCoordinate
    }

    fun projectsBetweenStationsAs(projection: ServiceMapProjection?) {
        defaultBetweenStationsProjection = projection
    }

    fun projectsBetweenStations(
        fromStation: StationReference,
        toStation: StationReference,
        progress: Double,
        projection: ServiceMapProjection?
    ) {
        betweenStationProjections[SegmentProgressKey(fromStation.id, toStation.id, progress)] = projection
    }

    fun projectsNextStopAnchorAs(projection: ServiceMapProjection?) {
        defaultNextStopAnchorProjection = projection
    }

    fun projectsNextStopAnchor(
        serviceId: ServiceId,
        projection: ServiceMapProjection?
    ) {
        nextStopAnchorProjections[serviceId] = projection
    }

    override fun projectStation(stationCoordinate: GeoCoordinate) =
        run {
            stationRequests += stationCoordinate
            if (stationProjections.containsKey(stationCoordinate)) {
                stationProjections[stationCoordinate]
            } else {
                defaultStationProjection
            }
        }

    override fun projectBetweenStationsAtProgress(
        fromStation: StationReference,
        toStation: StationReference,
        progress: Double
    ) =
        run {
            betweenStationRequests += BetweenStationsRequest(fromStation, toStation, progress)
            val key = SegmentProgressKey(fromStation.id, toStation.id, progress)

            if (betweenStationProjections.containsKey(key)) {
                betweenStationProjections[key]
            } else {
                defaultBetweenStationsProjection
            }
        }

    override fun projectNextStopAnchor(service: LiveRailService) =
        run {
            nextStopAnchorRequests += service
            if (nextStopAnchorProjections.containsKey(service.serviceId)) {
                nextStopAnchorProjections[service.serviceId]
            } else {
                defaultNextStopAnchorProjection
            }
        }
}

data class BetweenStationsRequest(
    val fromStation: StationReference,
    val toStation: StationReference,
    val progress: Double
)

private data class SegmentProgressKey(
    val fromStationId: StationId,
    val toStationId: StationId,
    val progress: Double
)
