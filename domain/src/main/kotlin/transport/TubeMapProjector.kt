package transport

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

interface TubeMapProjector {
    fun project(snapshot: LiveTubeSnapshot, lineMap: TubeLineMap): TubeMapSnapshot
}

interface TubePathSmoother {
    fun smooth(lineMap: TubeLineMap): TubeLineMap
}

class RealTubeMapProjector(
    private val tubePathSmoother: TubePathSmoother
) : TubeMapProjector {
    override fun project(snapshot: LiveTubeSnapshot, lineMap: TubeLineMap): TubeMapSnapshot {
        val smoothedLineMap = tubePathSmoother.smooth(lineMap)
        val projectedLines = smoothedLineMap.lines.associate { line ->
            line.id to ProjectedTubeLine(line)
        }
        val projectedLineList = projectedLines.values.toList()
        val projectedTubeStations = projectTubeStations(smoothedLineMap, projectedLines)

        return TubeMapSnapshot(
            snapshot.source,
            snapshot.generatedAt,
            snapshot.cached,
            snapshot.cacheAge,
            snapshot.stationsQueried,
            snapshot.stationsFailed,
            snapshot.partial,
            snapshot.trainCount,
            smoothedLineMap.lines,
            projectedTubeStations,
            snapshot.trains.mapNotNull { train -> projectTrain(train, projectedLineList) }
        )
    }

    private fun projectTubeStations(
        lineMap: TubeLineMap,
        projectedLines: Map<LineId, ProjectedTubeLine>
    ): List<TubeMapStation> =
        lineMap.lines
            .filter { line -> line.id in supportedTubeLineIdSet }
            .flatMap { line ->
                val projectedLine = projectedLines[line.id] ?: return@flatMap emptyList()
                line.sequences
                    .flatMap(TubeLineSequence::stations)
                    .distinctBy(StationReference::id)
                    .map { station ->
                        ProjectedTubeStationCandidate(
                            station.id,
                            station.name,
                            station.coordinate,
                            projectedLine.projectStation(station.coordinate) ?: station.coordinate,
                            line.id
                        )
                    }
            }
            .groupBy(ProjectedTubeStationCandidate::id)
            .values
            .map { candidates ->
                val firstCandidate = candidates.first()
                val projectedCoordinate = candidates
                    .minByOrNull { candidate ->
                        squaredDistance(candidate.projectedCoordinate, candidate.sourceCoordinate)
                    }
                    ?.projectedCoordinate
                    ?: firstCandidate.projectedCoordinate

                TubeMapStation(
                    firstCandidate.id,
                    firstCandidate.name,
                    projectedCoordinate,
                    candidates
                        .map(ProjectedTubeStationCandidate::lineId)
                        .distinctBy(LineId::value)
                        .sortedBy(LineId::value)
                )
            }
            .sortedBy { station -> station.name.value }

    private fun projectTrain(train: LiveTubeTrain, projectedLines: List<ProjectedTubeLine>): TubeMapTrain? {
        val lineId = train.lineIds.firstOrNull() ?: return null
        val lineName = train.lineNames.firstOrNull() ?: LineName(lineId.value)
        val projectedLine = projectedLines.firstOrNull { line -> line.line.id == lineId }
        val projection = projectedLine?.let { line -> projectTrainProjection(train, line) }
        val coordinate = projection?.coordinate ?: fallbackCoordinate(train)

        return TubeMapTrain(
            train.trainId,
            train.vehicleId,
            lineId,
            lineName,
            train.direction,
            train.destinationName,
            train.towards,
            train.currentLocation,
            train.nextStop,
            coordinate,
            projection?.heading,
            train.secondsToNextStop,
            train.expectedArrival,
            train.observedAt
        )
    }

    private fun projectTrainProjection(train: LiveTubeTrain, projectedLine: ProjectedTubeLine): TrainMapProjection? {
        val location = train.location
        val betweenProjection = location.fromStation?.let { fromStation ->
            location.toStation?.let { toStation ->
                projectedLine.projectBetweenStations(fromStation, toStation)
            }
        }
        if (betweenProjection != null) {
            return betweenProjection
        }

        val stationProjection = projectedLine.projectStationMovement(train)
        if (stationProjection != null) {
            return stationProjection
        }

        return fallbackCoordinate(train)?.let { coordinate ->
            projectedLine.projectCoordinate(coordinate)?.let { projectedCoordinate ->
                TrainMapProjection(projectedCoordinate, null)
            }
        }
    }

    private fun fallbackCoordinate(train: LiveTubeTrain): GeoCoordinate? =
        train.location.coordinate
            ?: train.nextStop?.coordinate
            ?: train.location.station?.coordinate
            ?: train.location.toStation?.coordinate
            ?: train.location.fromStation?.coordinate
}

class RealIdentityTubePathSmoother : TubePathSmoother {
    override fun smooth(lineMap: TubeLineMap) =
        lineMap
}

data class ProjectedTubeStationCandidate(
    val id: StationId,
    val name: StationName,
    val sourceCoordinate: GeoCoordinate,
    val projectedCoordinate: GeoCoordinate,
    val lineId: LineId
)

class RealTubePathSmoother(
    private val samplesPerSegment: Int
) : TubePathSmoother {
    override fun smooth(lineMap: TubeLineMap) =
        TubeLineMap(
            lineMap.lines.map { line ->
                TubeLine(
                    line.id,
                    line.name,
                    line.paths.map { path ->
                        TubeLinePath(smoothCoordinates(path.coordinates))
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

data class ProjectedTubeLine(
    val line: TubeLine
) {
    private val projectedPaths = line.paths.map(::ProjectedTubeLinePath)

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

    fun projectBetweenStations(
        fromStation: StationReference,
        toStation: StationReference
    ): TrainMapProjection? =
        findBetweenStationsProjection(fromStation, toStation)
            ?.toTrainMapProjection(0.5)

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

    fun projectStationMovement(train: LiveTubeTrain): TrainMapProjection? {
        val anchorStation = train.location.station ?: train.nextStop ?: return null
        val candidateSegments = matchingSequences(train.direction)
            .mapNotNull { sequence -> stationMovement(sequence, train.location.type, anchorStation.id) }
        if (candidateSegments.isEmpty()) {
            return projectStation(anchorStation.coordinate)?.let { coordinate ->
                TrainMapProjection(coordinate, null)
            }
        }

        return candidateSegments
            .flatMap { movement ->
                projectedPaths.mapNotNull { path -> projectStationMovement(path, movement) }
            }
            .minByOrNull(StationMovementProjection::distanceSquared)
            ?.toTrainMapProjection()
    }

    private fun matchingSequences(direction: TrainDirection?): List<TubeLineSequence> {
        if (line.sequences.isEmpty() || direction == null) {
            return emptyList()
        }

        val matchingDirections = line.sequences.filter { sequence -> sequence.direction == direction }

        return if (matchingDirections.isNotEmpty()) matchingDirections else line.sequences
    }

    private fun stationMovement(
        sequence: TubeLineSequence,
        locationType: LocationType,
        anchorStationId: StationId
    ): StationMovement? {
        val anchorIndex = sequence.stations.indexOfFirst { station -> station.id == anchorStationId }
        if (anchorIndex < 0) {
            return null
        }

        val anchorStation = sequence.stations[anchorIndex]
        val previousStation = sequence.stations.getOrNull(anchorIndex - 1)
        val nextStation = sequence.stations.getOrNull(anchorIndex + 1)

        return when (locationType) {
            LocationType.APPROACHING_STATION ->
                previousStation?.let { station -> StationMovement(station, anchorStation, anchorStation, AnchorPosition.END) }
            LocationType.AT_STATION,
            LocationType.DEPARTED_STATION,
            LocationType.NEAR_STATION ->
                nextStation?.let { station -> StationMovement(anchorStation, station, anchorStation, AnchorPosition.START) }
            LocationType.STATION_BOARD ->
                previousStation?.let { station -> StationMovement(station, anchorStation, anchorStation, AnchorPosition.END) }
            LocationType.BETWEEN_STATIONS,
            LocationType.UNKNOWN -> null
        }
    }

    private fun projectStationMovement(
        path: ProjectedTubeLinePath,
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
            movement.anchorPosition,
            fromProjection.distanceSquared + toProjection.distanceSquared + anchorProjection.distanceSquared
        )
    }
}

data class ProjectedTubeLinePath(
    val path: TubeLinePath
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
        travelEndDistance: Double,
        anchorPosition: AnchorPosition
    ): HeadingDegrees? {
        val travelLength = abs(travelEndDistance - travelStartDistance)
        if (travelLength < 0.0000001) {
            return null
        }

        val step = (travelLength / 4.0).coerceAtLeast(0.00001).coerceAtMost(travelLength)
        val startOffset = when (anchorPosition) {
            AnchorPosition.START -> 0.0
            AnchorPosition.MIDDLE -> ((travelLength / 2.0) - (step / 2.0)).coerceAtLeast(0.0)
            AnchorPosition.END -> (travelLength - step).coerceAtLeast(0.0)
        }
        val endOffset = when (anchorPosition) {
            AnchorPosition.START -> step.coerceAtMost(travelLength)
            AnchorPosition.MIDDLE -> ((travelLength / 2.0) + (step / 2.0)).coerceAtMost(travelLength)
            AnchorPosition.END -> travelLength
        }

        val startCoordinate = coordinateAt(travelDistanceAtOffset(travelStartDistance, travelEndDistance, startOffset))
        val endCoordinate = coordinateAt(travelDistanceAtOffset(travelStartDistance, travelEndDistance, endOffset))
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

        val midpointDistance = travelDistanceAtOffset(
            travelStartDistance,
            travelEndDistance,
            travelLength * clampedProgress
        )
        val step = (travelLength / 20.0).coerceAtLeast(0.00001).coerceAtMost(travelLength / 2.0)
        val startDistance = boundedTravelDistance(midpointDistance, travelStartDistance, travelEndDistance, -step)
        val endDistance = boundedTravelDistance(midpointDistance, travelStartDistance, travelEndDistance, step)
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

    private fun travelDistanceAtOffset(
        travelStartDistance: Double,
        travelEndDistance: Double,
        offset: Double
    ) =
        if (travelEndDistance >= travelStartDistance) {
            travelStartDistance + offset
        } else {
            travelStartDistance - offset
        }

    private fun boundedTravelDistance(
        currentDistance: Double,
        travelStartDistance: Double,
        travelEndDistance: Double,
        offset: Double
    ): Double {
        val lowerBound = minOf(travelStartDistance, travelEndDistance)
        val upperBound = maxOf(travelStartDistance, travelEndDistance)
        return (currentDistance + offset).coerceIn(lowerBound, upperBound)
    }
}

data class LinePathProjection(
    val path: ProjectedTubeLinePath,
    val coordinate: GeoCoordinate,
    val distanceSquared: Double,
    val distanceAlongPath: Double
)

data class BetweenStationsProjection(
    val path: ProjectedTubeLinePath,
    val fromProjection: LinePathProjection,
    val toProjection: LinePathProjection,
    val distanceSquared: Double
)

data class StationMovement(
    val fromStation: StationReference,
    val toStation: StationReference,
    val anchorStation: StationReference,
    val anchorPosition: AnchorPosition
)

data class StationMovementProjection(
    val path: ProjectedTubeLinePath,
    val fromProjection: LinePathProjection,
    val toProjection: LinePathProjection,
    val anchorProjection: LinePathProjection,
    val anchorPosition: AnchorPosition,
    val distanceSquared: Double
)

data class TrainMapProjection(
    val coordinate: GeoCoordinate,
    val heading: HeadingDegrees?
)

enum class AnchorPosition {
    START,
    MIDDLE,
    END
}

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

private fun StationMovementProjection.toTrainMapProjection() =
    TrainMapProjection(
        anchorProjection.coordinate,
        path.headingAlongTravel(fromProjection.distanceAlongPath, toProjection.distanceAlongPath, anchorPosition)
    )

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
