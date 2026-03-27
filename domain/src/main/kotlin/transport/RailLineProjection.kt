package transport

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

interface RailLineProjectionFactory {
    fun create(line: RailLine): RailLineProjection
}

interface RailLineProjection {
    val line: RailLine

    fun projectStation(stationCoordinate: GeoCoordinate): GeoCoordinate?
    fun projectBetweenStationsAtProgress(
        fromStation: StationReference,
        toStation: StationReference,
        progress: Double
    ): TrainMapProjection?

    fun projectNextStopAnchor(train: LiveRailTrain): TrainMapProjection?
}

interface RailLinePathProjection {
    val path: RailLinePath
    val totalLength: Double

    fun projectCoordinate(target: GeoCoordinate): PathCoordinateProjection?
    fun coordinateAt(distanceAlongPath: Double): GeoCoordinate?
    fun headingAlongTravel(
        travelStartDistance: Double,
        travelEndDistance: Double
    ): HeadingDegrees?

    fun headingAtProgress(
        travelStartDistance: Double,
        travelEndDistance: Double,
        progress: Double
    ): HeadingDegrees?
}

class RealRailLineProjectionFactory : RailLineProjectionFactory {
    override fun create(line: RailLine) =
        RealRailLineProjection(line)
}

class RealRailLineProjection(
    override val line: RailLine
) : RailLineProjection {
    private val projectedPaths = line.paths.map(::RealRailLinePathProjection)

    override fun projectStation(stationCoordinate: GeoCoordinate) =
        projectedPaths
            .mapNotNull { pathProjection -> pathProjection.projectCoordinate(stationCoordinate) }
            .minByOrNull(PathCoordinateProjection::distanceSquared)
            ?.coordinate

    override fun projectBetweenStationsAtProgress(
        fromStation: StationReference,
        toStation: StationReference,
        progress: Double
    ) =
        findBetweenStationsProjection(fromStation, toStation)
            ?.toTrainMapProjection(progress.coerceIn(0.0, 1.0))

    override fun projectNextStopAnchor(train: LiveRailTrain): TrainMapProjection? {
        val nextStop = train.nextStop
        val coordinate = nextStop?.let { stop -> projectStation(stop.coordinate) }

        return if (nextStop == null || coordinate == null) {
            null
        } else {
            TrainMapProjection(
                coordinate,
                stationAnchorHeading(train.direction, nextStop)
            )
        }
    }

    private fun findBetweenStationsProjection(
        fromStation: StationReference,
        toStation: StationReference
    ) =
        projectedPaths
            .mapNotNull { pathProjection ->
                betweenStationsProjection(pathProjection, fromStation, toStation)
            }
            .minByOrNull(BetweenStationsProjection::distanceSquared)

    private fun stationAnchorHeading(
        direction: TrainDirection?,
        nextStop: StationReference
    ) =
        matchingSequences(direction)
            .mapNotNull { sequence -> nextStopMovement(sequence, nextStop.id) }
            .flatMap { movement ->
                projectedPaths.mapNotNull { pathProjection -> projectStationMovement(pathProjection, movement) }
            }
            .minByOrNull(StationMovementProjection::distanceSquared)
            ?.heading()

    private fun betweenStationsProjection(
        pathProjection: RailLinePathProjection,
        fromStation: StationReference,
        toStation: StationReference
    ): BetweenStationsProjection? {
        val fromProjection = pathProjection.projectCoordinate(fromStation.coordinate)
        val toProjection = pathProjection.projectCoordinate(toStation.coordinate)

        return if (fromProjection == null || toProjection == null) {
            null
        } else {
            BetweenStationsProjection(
                pathProjection,
                fromProjection,
                toProjection,
                fromProjection.distanceSquared + toProjection.distanceSquared
            )
        }
    }

    private fun matchingSequences(direction: TrainDirection?) =
        if (line.sequences.isEmpty() || direction == null) {
            emptyList()
        } else {
            line.sequences.filter { sequence -> sequence.direction == direction }
        }

    private fun nextStopMovement(
        sequence: RailLineSequence,
        nextStopStationId: StationId
    ): StationMovement? {
        val nextStopIndex = sequence.stations.indexOfFirst { station -> station.id == nextStopStationId }
        val anchorStation = sequence.stations.getOrNull(nextStopIndex)
        val nextStation = sequence.stations.getOrNull(nextStopIndex + 1)
        val previousStation = sequence.stations.getOrNull(nextStopIndex - 1)

        return when {
            anchorStation == null -> null
            nextStation != null -> StationMovement(anchorStation, nextStation, anchorStation)
            previousStation != null -> StationMovement(previousStation, anchorStation, anchorStation)
            else -> null
        }
    }

    private fun projectStationMovement(
        pathProjection: RailLinePathProjection,
        movement: StationMovement
    ): StationMovementProjection? {
        val fromProjection = pathProjection.projectCoordinate(movement.fromStation.coordinate)
        val toProjection = pathProjection.projectCoordinate(movement.toStation.coordinate)
        val anchorProjection = pathProjection.projectCoordinate(movement.anchorStation.coordinate)

        return if (fromProjection == null || toProjection == null || anchorProjection == null) {
            null
        } else {
            StationMovementProjection(
                pathProjection,
                fromProjection,
                toProjection,
                anchorProjection,
                fromProjection.distanceSquared + toProjection.distanceSquared + anchorProjection.distanceSquared
            )
        }
    }
}

class RealRailLinePathProjection(
    override val path: RailLinePath
) : RailLinePathProjection {
    private val cumulativeLengths = buildCumulativeLengths(path.coordinates)
    override val totalLength = cumulativeLengths.lastOrNull() ?: 0.0

    override fun projectCoordinate(target: GeoCoordinate): PathCoordinateProjection? =
        when {
            path.coordinates.isEmpty() -> null
            path.coordinates.size == 1 -> {
                val onlyPoint = path.coordinates.first()
                PathCoordinateProjection(this, onlyPoint, squaredDistance(onlyPoint, target), 0.0)
            }
            else ->
                (0 until path.coordinates.lastIndex)
                    .map { segmentIndex -> projectOntoSegment(target, segmentIndex) }
                    .minByOrNull(PathCoordinateProjection::distanceSquared)
        }

    override fun coordinateAt(distanceAlongPath: Double): GeoCoordinate? =
        when {
            path.coordinates.isEmpty() -> null
            distanceAlongPath <= 0.0 || path.coordinates.size == 1 -> path.coordinates.first()
            distanceAlongPath >= totalLength -> path.coordinates.last()
            else ->
                cumulativeLengths.indexOfFirst { segmentDistance -> segmentDistance >= distanceAlongPath }
                    .let { segmentIndex ->
                        if (segmentIndex <= 0) {
                            path.coordinates.first()
                        } else {
                            val startCoordinate = path.coordinates[segmentIndex - 1]
                            val endCoordinate = path.coordinates[segmentIndex]
                            val segmentStart = cumulativeLengths[segmentIndex - 1]
                            val segmentLength = cumulativeLengths[segmentIndex] - segmentStart

                            if (segmentLength == 0.0) {
                                startCoordinate
                            } else {
                                val fraction = (distanceAlongPath - segmentStart) / segmentLength
                                interpolate(startCoordinate, endCoordinate, fraction)
                            }
                        }
                    }
        }

    override fun headingAlongTravel(
        travelStartDistance: Double,
        travelEndDistance: Double
    ): HeadingDegrees? =
        abs(travelEndDistance - travelStartDistance).let { travelLength ->
            if (travelLength < 0.0000001) {
                null
            } else {
                val step = (travelLength / 4.0).coerceAtLeast(0.00001).coerceAtMost(travelLength)
                val startCoordinate = coordinateAt(travelStartDistance)
                val endCoordinate = coordinateAt(offsetAlongTravel(travelStartDistance, travelStartDistance, travelEndDistance, step))

                if (startCoordinate == null || endCoordinate == null) {
                    null
                } else {
                    bearingBetween(startCoordinate, endCoordinate)
                }
            }
        }

    override fun headingAtProgress(
        travelStartDistance: Double,
        travelEndDistance: Double,
        progress: Double
    ): HeadingDegrees? =
        abs(travelEndDistance - travelStartDistance).let { travelLength ->
            if (travelLength < 0.0000001) {
                null
            } else {
                val clampedProgress = progress.coerceIn(0.0, 1.0)
                val midpointDistance = offsetAlongTravel(
                    travelStartDistance,
                    travelStartDistance,
                    travelEndDistance,
                    travelLength * clampedProgress
                )
                val step = (travelLength / 20.0).coerceAtLeast(0.00001).coerceAtMost(travelLength / 2.0)
                val lowerBound = minOf(travelStartDistance, travelEndDistance)
                val upperBound = maxOf(travelStartDistance, travelEndDistance)
                val startDistance = offsetAlongTravel(midpointDistance, travelStartDistance, travelEndDistance, -step).coerceIn(lowerBound, upperBound)
                val endDistance = offsetAlongTravel(midpointDistance, travelStartDistance, travelEndDistance, step).coerceIn(lowerBound, upperBound)
                val startCoordinate = coordinateAt(startDistance)
                val endCoordinate = coordinateAt(endDistance)

                if (startCoordinate == null || endCoordinate == null) {
                    null
                } else {
                    bearingBetween(startCoordinate, endCoordinate)
                }
            }
        }

    private fun projectOntoSegment(target: GeoCoordinate, segmentIndex: Int): PathCoordinateProjection =
        path.coordinates[segmentIndex].let { startCoordinate ->
            path.coordinates[segmentIndex + 1].let { endCoordinate ->
                val segmentLat = endCoordinate.lat - startCoordinate.lat
                val segmentLon = endCoordinate.lon - startCoordinate.lon
                val segmentLengthSquared = segmentLat.pow(2) + segmentLon.pow(2)

                if (segmentLengthSquared == 0.0) {
                    PathCoordinateProjection(
                        this,
                        startCoordinate,
                        squaredDistance(startCoordinate, target),
                        cumulativeLengths[segmentIndex]
                    )
                } else {
                    val targetLat = target.lat - startCoordinate.lat
                    val targetLon = target.lon - startCoordinate.lon
                    val fraction = ((targetLat * segmentLat) + (targetLon * segmentLon)) / segmentLengthSquared
                    val clampedFraction = fraction.coerceIn(0.0, 1.0)
                    val projectedCoordinate = interpolate(startCoordinate, endCoordinate, clampedFraction)
                    val segmentLength = sqrt(segmentLengthSquared)
                    val segmentStartDistance = cumulativeLengths[segmentIndex]

                    PathCoordinateProjection(
                        this,
                        projectedCoordinate,
                        squaredDistance(projectedCoordinate, target),
                        segmentStartDistance + (segmentLength * clampedFraction)
                    )
                }
            }
        }

    private fun buildCumulativeLengths(coordinates: List<GeoCoordinate>): List<Double> =
        if (coordinates.isEmpty()) {
            emptyList()
        } else {
            val cumulative = mutableListOf(0.0)
            for (index in 1 until coordinates.size) {
                cumulative += cumulative.last() + distance(coordinates[index - 1], coordinates[index])
            }
            cumulative
        }

    private fun offsetAlongTravel(
        referenceDistance: Double,
        travelStartDistance: Double,
        travelEndDistance: Double,
        offset: Double
    ) =
        if (travelEndDistance >= travelStartDistance) {
            referenceDistance + offset
        } else {
            referenceDistance - offset
        }
}

data class PathCoordinateProjection(
    val path: RailLinePathProjection,
    val coordinate: GeoCoordinate,
    val distanceSquared: Double,
    val distanceAlongPath: Double
)

data class BetweenStationsProjection(
    val path: RailLinePathProjection,
    val fromProjection: PathCoordinateProjection,
    val toProjection: PathCoordinateProjection,
    val distanceSquared: Double
)

data class StationMovement(
    val fromStation: StationReference,
    val toStation: StationReference,
    val anchorStation: StationReference
)

data class StationMovementProjection(
    val path: RailLinePathProjection,
    val fromProjection: PathCoordinateProjection,
    val toProjection: PathCoordinateProjection,
    val anchorProjection: PathCoordinateProjection,
    val distanceSquared: Double
)

data class TrainMapProjection(
    val coordinate: GeoCoordinate,
    val heading: HeadingDegrees?
)

private fun BetweenStationsProjection.toTrainMapProjection(progress: Double): TrainMapProjection? =
    (fromProjection.distanceAlongPath +
        ((toProjection.distanceAlongPath - fromProjection.distanceAlongPath) * progress.coerceIn(0.0, 1.0)))
        .let { progressDistance ->
            path.coordinateAt(progressDistance)?.let { coordinate ->
                val heading = path.headingAtProgress(
                    fromProjection.distanceAlongPath,
                    toProjection.distanceAlongPath,
                    progress
                )

                TrainMapProjection(coordinate, heading)
            }
        }

private fun StationMovementProjection.heading() =
    path.headingAlongTravel(fromProjection.distanceAlongPath, toProjection.distanceAlongPath)
