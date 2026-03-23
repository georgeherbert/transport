package transport

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

interface RailMapProjector {
    fun project(snapshot: LiveRailSnapshot, lineMap: RailLineMap): RailMapSnapshot
}

interface RailPathSmoother {
    fun smooth(lineMap: RailLineMap): RailLineMap
}

class RealRailMapProjector(
    private val railPathSmoother: RailPathSmoother
) : RailMapProjector {
    override fun project(snapshot: LiveRailSnapshot, lineMap: RailLineMap): RailMapSnapshot {
        val smoothedLineMap = railPathSmoother.smooth(lineMap)
        val projectedLines = smoothedLineMap.lines.associate { line ->
            line.id to ProjectedRailLine(line)
        }
        val projectedLineList = projectedLines.values.toList()
        val projectedStations = projectStations(smoothedLineMap, projectedLines)

        return RailMapSnapshot(
            snapshot.source,
            snapshot.generatedAt,
            snapshot.cached,
            snapshot.cacheAge,
            snapshot.stationsQueried,
            snapshot.stationsFailed,
            snapshot.partial,
            snapshot.trainCount,
            smoothedLineMap.lines,
            projectedStations,
            snapshot.trains.mapNotNull { train -> projectTrain(train, projectedLineList) }
        )
    }

    private fun projectStations(
        lineMap: RailLineMap,
        projectedLines: Map<LineId, ProjectedRailLine>
    ): List<MapStation> =
        lineMap.lines
            .filter { line -> line.id in supportedMapStationLineIdSet }
            .flatMap { line ->
                val projectedLine = projectedLines[line.id] ?: return@flatMap emptyList()
                line.sequences
                    .flatMap(RailLineSequence::stations)
                    .distinctBy(StationReference::id)
                    .map { station ->
                        ProjectedStationCandidate(
                            station.id,
                            station.name,
                            station.coordinate,
                            projectedLine.projectStation(station.coordinate) ?: station.coordinate,
                            line.id
                        )
                    }
            }
            .groupBy(ProjectedStationCandidate::id)
            .values
            .map { candidates ->
                val firstCandidate = candidates.first()
                val projectedCoordinate = candidates
                    .minByOrNull { candidate ->
                        squaredDistance(candidate.projectedCoordinate, candidate.sourceCoordinate)
                    }
                    ?.projectedCoordinate
                    ?: firstCandidate.projectedCoordinate

                MapStation(
                    firstCandidate.id,
                    firstCandidate.name,
                    projectedCoordinate,
                    candidates
                        .map(ProjectedStationCandidate::lineId)
                        .distinctBy(LineId::value)
                        .sortedBy(LineId::value)
                )
            }
            .sortedBy { station -> station.name.value }

    private fun projectTrain(train: LiveRailTrain, projectedLines: List<ProjectedRailLine>): RailMapTrain? {
        val lineId = train.lineIds.firstOrNull() ?: return null
        val lineName = train.lineNames.firstOrNull() ?: LineName(lineId.value)
        val projectedLine = projectedLines.firstOrNull { line -> line.line.id == lineId }
        val projection = projectedLine?.let { line -> projectTrainProjection(train, line) }

        return RailMapTrain(
            train.trainId,
            train.vehicleId,
            lineId,
            lineName,
            train.direction,
            train.destinationName,
            train.towards,
            train.currentLocation,
            train.nextStop,
            projection?.coordinate,
            projection?.heading,
            train.secondsToNextStop,
            train.expectedArrival,
            train.observedAt
        )
    }

    private fun projectTrainProjection(train: LiveRailTrain, projectedLine: ProjectedRailLine): TrainMapProjection? =
        projectedLine.projectNextStopAnchor(train)
}

class RealIdentityRailPathSmoother : RailPathSmoother {
    override fun smooth(lineMap: RailLineMap) =
        lineMap
}

data class ProjectedStationCandidate(
    val id: StationId,
    val name: StationName,
    val sourceCoordinate: GeoCoordinate,
    val projectedCoordinate: GeoCoordinate,
    val lineId: LineId
)

class RealRailPathSmoother(
    private val samplesPerSegment: Int
) : RailPathSmoother {
    override fun smooth(lineMap: RailLineMap) =
        RailLineMap(
            lineMap.lines.map { line ->
                RailLine(
                    line.id,
                    line.name,
                    line.paths.map { path ->
                        RailLinePath(smoothCoordinates(path.coordinates))
                    },
                    line.sequences
                )
            }
        )

    private fun smoothCoordinates(coordinates: List<GeoCoordinate>): List<GeoCoordinate> {
        if (coordinates.size < 3) {
            return coordinates
        }

        val smoothed = mutableListOf<GeoCoordinate>()
        smoothed += coordinates.first()

        for (index in 0 until coordinates.lastIndex) {
            val p0 = coordinates.getOrElse(index - 1) { coordinates[index] }
            val p1 = coordinates[index]
            val p2 = coordinates[index + 1]
            val p3 = coordinates.getOrElse(index + 2) { coordinates[index + 1] }
            for (step in 1 until samplesPerSegment) {
                val t = step.toDouble() / samplesPerSegment.toDouble()
                smoothed += catmullRomPoint(p0, p1, p2, p3, t)
            }
            smoothed += p2
        }

        return smoothed
    }

    private fun catmullRomPoint(
        p0: GeoCoordinate,
        p1: GeoCoordinate,
        p2: GeoCoordinate,
        p3: GeoCoordinate,
        t: Double
    ): GeoCoordinate {
        val t0 = 0.0
        val t1 = nextParameter(t0, p0, p1)
        val t2 = nextParameter(t1, p1, p2)
        val t3 = nextParameter(t2, p2, p3)
        val pointT = t1 + ((t2 - t1) * t)

        val a1 = interpolateByParameter(p0, p1, t0, t1, pointT)
        val a2 = interpolateByParameter(p1, p2, t1, t2, pointT)
        val a3 = interpolateByParameter(p2, p3, t2, t3, pointT)
        val b1 = interpolateByParameter(a1, a2, t0, t2, pointT)
        val b2 = interpolateByParameter(a2, a3, t1, t3, pointT)

        return interpolateByParameter(b1, b2, t1, t2, pointT)
    }

    private fun nextParameter(
        currentParameter: Double,
        currentPoint: GeoCoordinate,
        nextPoint: GeoCoordinate
    ): Double =
        currentParameter + distance(currentPoint, nextPoint).pow(0.5)

    private fun interpolateByParameter(
        startCoordinate: GeoCoordinate,
        endCoordinate: GeoCoordinate,
        startParameter: Double,
        endParameter: Double,
        currentParameter: Double
    ): GeoCoordinate {
        if (abs(endParameter - startParameter) < 0.0000001) {
            return endCoordinate
        }

        val fraction = (currentParameter - startParameter) / (endParameter - startParameter)
        return interpolate(startCoordinate, endCoordinate, fraction)
    }
}

data class ProjectedRailLine(
    val line: RailLine
) {
    private val projectedPaths = line.paths.map(::ProjectedRailLinePath)

    fun projectStation(stationCoordinate: GeoCoordinate): GeoCoordinate? =
        projectedPaths
            .mapNotNull { path -> path.projectCoordinate(stationCoordinate) }
            .minByOrNull(LinePathProjection::distanceSquared)
            ?.coordinate

    fun projectCoordinate(coordinate: GeoCoordinate): GeoCoordinate? =
        projectedPaths
            .mapNotNull { path -> path.projectCoordinate(coordinate) }
            .minByOrNull(LinePathProjection::distanceSquared)
            ?.coordinate

    fun projectBetweenStationsAtProgress(
        fromStation: StationReference,
        toStation: StationReference,
        progress: Double
    ): TrainMapProjection? =
        findBetweenStationsProjection(fromStation, toStation)
            ?.toTrainMapProjection(progress.coerceIn(0.0, 1.0))

    private fun findBetweenStationsProjection(
        fromStation: StationReference,
        toStation: StationReference
    ): BetweenStationsProjection? =
        projectedPaths
            .mapNotNull { path ->
                val fromProjection = path.projectCoordinate(fromStation.coordinate)
                val toProjection = path.projectCoordinate(toStation.coordinate)
                if (fromProjection == null || toProjection == null) {
                    null
                } else {
                    BetweenStationsProjection(
                        path,
                        fromProjection,
                        toProjection,
                        fromProjection.distanceSquared + toProjection.distanceSquared
                    )
                }
            }
            .minByOrNull(BetweenStationsProjection::distanceSquared)

    fun projectNextStopAnchor(train: LiveRailTrain): TrainMapProjection? {
        val nextStop = train.nextStop ?: return null
        val coordinate = projectStation(nextStop.coordinate) ?: return null
        val heading = matchingSequences(train.direction)
            .mapNotNull { sequence -> nextStopMovement(sequence, nextStop.id) }
            .flatMap { movement ->
                projectedPaths.mapNotNull { path -> projectStationMovement(path, movement) }
            }
            .minByOrNull(StationMovementProjection::distanceSquared)
            ?.heading()

        return TrainMapProjection(coordinate, heading)
    }

    private fun matchingSequences(direction: TrainDirection?): List<RailLineSequence> {
        if (line.sequences.isEmpty() || direction == null) {
            return emptyList()
        }
        return line.sequences.filter { sequence -> sequence.direction == direction }
    }

    private fun nextStopMovement(
        sequence: RailLineSequence,
        nextStopStationId: StationId
    ): StationMovement? {
        val nextStopIndex = sequence.stations.indexOfFirst { station -> station.id == nextStopStationId }
        if (nextStopIndex < 0) {
            return null
        }

        val anchorStation = sequence.stations[nextStopIndex]
        val nextStation = sequence.stations.getOrNull(nextStopIndex + 1)
        val previousStation = sequence.stations.getOrNull(nextStopIndex - 1)

        return nextStation?.let { station ->
            StationMovement(anchorStation, station, anchorStation)
        } ?: previousStation?.let { station ->
            StationMovement(station, anchorStation, anchorStation)
        }
    }

    private fun projectStationMovement(
        path: ProjectedRailLinePath,
        movement: StationMovement
    ): StationMovementProjection? {
        val fromProjection = path.projectCoordinate(movement.fromStation.coordinate)
        val toProjection = path.projectCoordinate(movement.toStation.coordinate)
        val anchorProjection = path.projectCoordinate(movement.anchorStation.coordinate)
        if (fromProjection == null || toProjection == null || anchorProjection == null) {
            return null
        }

        return StationMovementProjection(
            path,
            fromProjection,
            toProjection,
            anchorProjection,
            fromProjection.distanceSquared + toProjection.distanceSquared + anchorProjection.distanceSquared
        )
    }
}

data class ProjectedRailLinePath(
    val path: RailLinePath
) {
    private val cumulativeLengths = buildCumulativeLengths(path.coordinates)
    val totalLength = cumulativeLengths.lastOrNull() ?: 0.0

    fun projectCoordinate(target: GeoCoordinate): LinePathProjection? {
        if (path.coordinates.isEmpty()) {
            return null
        }

        if (path.coordinates.size == 1) {
            val onlyPoint = path.coordinates.first()
            return LinePathProjection(this, onlyPoint, squaredDistance(onlyPoint, target), 0.0)
        }

        return (0 until path.coordinates.lastIndex)
            .map { segmentIndex -> projectOntoSegment(target, segmentIndex) }
            .minByOrNull(LinePathProjection::distanceSquared)
    }

    fun coordinateAt(distanceAlongPath: Double): GeoCoordinate? {
        if (path.coordinates.isEmpty()) {
            return null
        }

        if (distanceAlongPath <= 0.0 || path.coordinates.size == 1) {
            return path.coordinates.first()
        }

        if (distanceAlongPath >= totalLength) {
            return path.coordinates.last()
        }

        val segmentIndex = cumulativeLengths.indexOfFirst { segmentDistance -> segmentDistance >= distanceAlongPath }
        if (segmentIndex <= 0) {
            return path.coordinates.first()
        }

        val startCoordinate = path.coordinates[segmentIndex - 1]
        val endCoordinate = path.coordinates[segmentIndex]
        val segmentStart = cumulativeLengths[segmentIndex - 1]
        val segmentLength = cumulativeLengths[segmentIndex] - segmentStart
        if (segmentLength == 0.0) {
            return startCoordinate
        }

        val fraction = (distanceAlongPath - segmentStart) / segmentLength
        return interpolate(startCoordinate, endCoordinate, fraction)
    }

    fun headingAlongTravel(
        travelStartDistance: Double,
        travelEndDistance: Double
    ): HeadingDegrees? {
        val travelLength = abs(travelEndDistance - travelStartDistance)
        if (travelLength < 0.0000001) {
            return null
        }

        val step = (travelLength / 4.0).coerceAtLeast(0.00001).coerceAtMost(travelLength)
        val startCoordinate = coordinateAt(travelStartDistance)
        val endCoordinate = coordinateAt(offsetAlongTravel(travelStartDistance, travelStartDistance, travelEndDistance, step))
        if (startCoordinate == null || endCoordinate == null) {
            return null
        }

        return bearingBetween(startCoordinate, endCoordinate)
    }

    fun headingAtProgress(
        travelStartDistance: Double,
        travelEndDistance: Double,
        progress: Double
    ): HeadingDegrees? {
        val clampedProgress = progress.coerceIn(0.0, 1.0)
        val travelLength = abs(travelEndDistance - travelStartDistance)
        if (travelLength < 0.0000001) {
            return null
        }

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
            return null
        }

        return bearingBetween(startCoordinate, endCoordinate)
    }

    private fun projectOntoSegment(target: GeoCoordinate, segmentIndex: Int): LinePathProjection {
        val startCoordinate = path.coordinates[segmentIndex]
        val endCoordinate = path.coordinates[segmentIndex + 1]
        val segmentLat = endCoordinate.lat - startCoordinate.lat
        val segmentLon = endCoordinate.lon - startCoordinate.lon
        val segmentLengthSquared = segmentLat.pow(2) + segmentLon.pow(2)
        if (segmentLengthSquared == 0.0) {
            return LinePathProjection(
                this,
                startCoordinate,
                squaredDistance(startCoordinate, target),
                cumulativeLengths[segmentIndex]
            )
        }

        val targetLat = target.lat - startCoordinate.lat
        val targetLon = target.lon - startCoordinate.lon
        val fraction = ((targetLat * segmentLat) + (targetLon * segmentLon)) / segmentLengthSquared
        val clampedFraction = fraction.coerceIn(0.0, 1.0)
        val projectedCoordinate = interpolate(startCoordinate, endCoordinate, clampedFraction)
        val segmentLength = sqrt(segmentLengthSquared)
        val segmentStartDistance = cumulativeLengths[segmentIndex]

        return LinePathProjection(
            this,
            projectedCoordinate,
            squaredDistance(projectedCoordinate, target),
            segmentStartDistance + (segmentLength * clampedFraction)
        )
    }

    private fun buildCumulativeLengths(coordinates: List<GeoCoordinate>): List<Double> {
        if (coordinates.isEmpty()) {
            return emptyList()
        }

        val cumulative = mutableListOf(0.0)
        for (index in 1 until coordinates.size) {
            cumulative += cumulative.last() + distance(coordinates[index - 1], coordinates[index])
        }
        return cumulative
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

data class LinePathProjection(
    val path: ProjectedRailLinePath,
    val coordinate: GeoCoordinate,
    val distanceSquared: Double,
    val distanceAlongPath: Double
)

data class BetweenStationsProjection(
    val path: ProjectedRailLinePath,
    val fromProjection: LinePathProjection,
    val toProjection: LinePathProjection,
    val distanceSquared: Double
)

data class StationMovement(
    val fromStation: StationReference,
    val toStation: StationReference,
    val anchorStation: StationReference
)

data class StationMovementProjection(
    val path: ProjectedRailLinePath,
    val fromProjection: LinePathProjection,
    val toProjection: LinePathProjection,
    val anchorProjection: LinePathProjection,
    val distanceSquared: Double
)

data class TrainMapProjection(
    val coordinate: GeoCoordinate,
    val heading: HeadingDegrees?
)

private fun BetweenStationsProjection.toTrainMapProjection(progress: Double): TrainMapProjection? {
    val progressDistance = fromProjection.distanceAlongPath +
        ((toProjection.distanceAlongPath - fromProjection.distanceAlongPath) * progress.coerceIn(0.0, 1.0))
    val coordinate = path.coordinateAt(progressDistance) ?: return null
    val heading = path.headingAtProgress(
        fromProjection.distanceAlongPath,
        toProjection.distanceAlongPath,
        progress
    )
    return TrainMapProjection(coordinate, heading)
}

private fun StationMovementProjection.heading() =
    path.headingAlongTravel(fromProjection.distanceAlongPath, toProjection.distanceAlongPath)

private fun squaredDistance(left: GeoCoordinate, right: GeoCoordinate): Double =
    (left.lat - right.lat).pow(2) + (left.lon - right.lon).pow(2)

private fun distance(left: GeoCoordinate, right: GeoCoordinate): Double =
    sqrt(squaredDistance(left, right))

private fun bearingBetween(startCoordinate: GeoCoordinate, endCoordinate: GeoCoordinate): HeadingDegrees? {
    val startProjected = projectToMercator(startCoordinate)
    val endProjected = projectToMercator(endCoordinate)
    val xDelta = endProjected.x - startProjected.x
    val yDelta = endProjected.y - startProjected.y
    if (abs(xDelta) < 0.0000001 && abs(yDelta) < 0.0000001) {
        return null
    }

    val rawDegrees = Math.toDegrees(atan2(xDelta, yDelta))
    val normalizedDegrees = (rawDegrees + 360.0) % 360.0
    return HeadingDegrees(normalizedDegrees)
}

private fun projectToMercator(coordinate: GeoCoordinate) =
    MapPlaneCoordinate(
        Math.toRadians(coordinate.lon),
        ln(tan((Math.PI / 4.0) + (Math.toRadians(coordinate.lat) / 2.0)))
    )

private fun interpolate(startCoordinate: GeoCoordinate, endCoordinate: GeoCoordinate, fraction: Double) =
    GeoCoordinate(
        startCoordinate.lat + ((endCoordinate.lat - startCoordinate.lat) * fraction),
        startCoordinate.lon + ((endCoordinate.lon - startCoordinate.lon) * fraction)
    )

data class MapPlaneCoordinate(
    val x: Double,
    val y: Double
)
