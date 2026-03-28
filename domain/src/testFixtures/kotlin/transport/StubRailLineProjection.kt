package transport

class StubRailLineProjection(
    override val line: RailLine
) : RailLineProjection {
    private var defaultStationProjection: GeoCoordinate? = null
    private var defaultBetweenStationsProjection: TrainMapProjection? = null
    private var defaultNextStopAnchorProjection: TrainMapProjection? = null
    private val stationProjections = mutableMapOf<GeoCoordinate, GeoCoordinate?>()
    private val betweenStationProjections = mutableMapOf<SegmentProgressKey, TrainMapProjection?>()
    private val nextStopAnchorProjections = mutableMapOf<TrainId, TrainMapProjection?>()

    val stationRequests = mutableListOf<GeoCoordinate>()
    val betweenStationRequests = mutableListOf<BetweenStationsRequest>()
    val nextStopAnchorRequests = mutableListOf<LiveRailTrain>()

    fun projectsStationAs(projectedCoordinate: GeoCoordinate?) {
        defaultStationProjection = projectedCoordinate
    }

    fun projectsStation(
        stationCoordinate: GeoCoordinate,
        projectedCoordinate: GeoCoordinate?
    ) {
        stationProjections[stationCoordinate] = projectedCoordinate
    }

    fun projectsBetweenStationsAs(projection: TrainMapProjection?) {
        defaultBetweenStationsProjection = projection
    }

    fun projectsBetweenStations(
        fromStation: StationReference,
        toStation: StationReference,
        progress: Double,
        projection: TrainMapProjection?
    ) {
        betweenStationProjections[SegmentProgressKey(fromStation.id, toStation.id, progress)] = projection
    }

    fun projectsNextStopAnchorAs(projection: TrainMapProjection?) {
        defaultNextStopAnchorProjection = projection
    }

    fun projectsNextStopAnchor(
        trainId: TrainId,
        projection: TrainMapProjection?
    ) {
        nextStopAnchorProjections[trainId] = projection
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

    override fun projectNextStopAnchor(train: LiveRailTrain) =
        run {
            nextStopAnchorRequests += train
            if (nextStopAnchorProjections.containsKey(train.trainId)) {
                nextStopAnchorProjections[train.trainId]
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
