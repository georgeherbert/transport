package transport

class StubRailLinePathProjection(
    override val path: RailLinePath,
    override val totalLength: Double
) : RailLinePathProjection {
    private var defaultCoordinateProjection: PathCoordinateProjection? = null
    private var defaultCoordinateAt: GeoCoordinate? = null
    private var defaultHeadingAlongTravel: HeadingDegrees? = null
    private var defaultHeadingAtProgress: HeadingDegrees? = null
    private val coordinateProjections = mutableMapOf<GeoCoordinate, PathCoordinateProjection?>()
    private val coordinatesAtDistance = mutableMapOf<Double, GeoCoordinate?>()
    private val travelHeadings = mutableMapOf<TravelRangeKey, HeadingDegrees?>()
    private val progressHeadings = mutableMapOf<TravelProgressKey, HeadingDegrees?>()

    val projectCoordinateRequests = mutableListOf<GeoCoordinate>()
    val coordinateAtRequests = mutableListOf<Double>()
    val headingAlongTravelRequests = mutableListOf<TravelRangeRequest>()
    val headingAtProgressRequests = mutableListOf<TravelProgressRequest>()

    fun projectsCoordinateAs(projection: PathCoordinateProjection?) {
        defaultCoordinateProjection = projection
    }

    fun projectsCoordinate(
        target: GeoCoordinate,
        projection: PathCoordinateProjection?
    ) {
        coordinateProjections[target] = projection
    }

    fun coordinateAtReturns(coordinate: GeoCoordinate?) {
        defaultCoordinateAt = coordinate
    }

    fun coordinateAt(
        distanceAlongPath: Double,
        coordinate: GeoCoordinate?
    ) {
        coordinatesAtDistance[distanceAlongPath] = coordinate
    }

    fun headingAlongTravelReturns(heading: HeadingDegrees?) {
        defaultHeadingAlongTravel = heading
    }

    fun headingAlongTravel(
        travelStartDistance: Double,
        travelEndDistance: Double,
        heading: HeadingDegrees?
    ) {
        travelHeadings[TravelRangeKey(travelStartDistance, travelEndDistance)] = heading
    }

    fun headingAtProgressReturns(heading: HeadingDegrees?) {
        defaultHeadingAtProgress = heading
    }

    fun headingAtProgress(
        travelStartDistance: Double,
        travelEndDistance: Double,
        progress: Double,
        heading: HeadingDegrees?
    ) {
        progressHeadings[TravelProgressKey(travelStartDistance, travelEndDistance, progress)] = heading
    }

    override fun projectCoordinate(target: GeoCoordinate) =
        run {
            projectCoordinateRequests += target
            if (coordinateProjections.containsKey(target)) {
                coordinateProjections[target]
            } else {
                defaultCoordinateProjection
            }
        }

    override fun coordinateAt(distanceAlongPath: Double) =
        run {
            coordinateAtRequests += distanceAlongPath
            if (coordinatesAtDistance.containsKey(distanceAlongPath)) {
                coordinatesAtDistance[distanceAlongPath]
            } else {
                defaultCoordinateAt
            }
        }

    override fun headingAlongTravel(
        travelStartDistance: Double,
        travelEndDistance: Double
    ) =
        run {
            headingAlongTravelRequests += TravelRangeRequest(travelStartDistance, travelEndDistance)
            val key = TravelRangeKey(travelStartDistance, travelEndDistance)

            if (travelHeadings.containsKey(key)) {
                travelHeadings[key]
            } else {
                defaultHeadingAlongTravel
            }
        }

    override fun headingAtProgress(
        travelStartDistance: Double,
        travelEndDistance: Double,
        progress: Double
    ) =
        run {
            headingAtProgressRequests += TravelProgressRequest(travelStartDistance, travelEndDistance, progress)
            val key = TravelProgressKey(travelStartDistance, travelEndDistance, progress)

            if (progressHeadings.containsKey(key)) {
                progressHeadings[key]
            } else {
                defaultHeadingAtProgress
            }
        }
}

data class TravelRangeRequest(
    val travelStartDistance: Double,
    val travelEndDistance: Double
)

data class TravelProgressRequest(
    val travelStartDistance: Double,
    val travelEndDistance: Double,
    val progress: Double
)

private data class TravelRangeKey(
    val travelStartDistance: Double,
    val travelEndDistance: Double
)

private data class TravelProgressKey(
    val travelStartDistance: Double,
    val travelEndDistance: Double,
    val progress: Double
)
