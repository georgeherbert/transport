package transport

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

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
        val projectedLines = smoothedLineMap.lines.map(::ProjectedTubeLine)

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
            snapshot.trains.mapNotNull { train -> projectTrain(train, projectedLines) }
        )
    }

    private fun projectTrain(train: LiveTubeTrain, projectedLines: List<ProjectedTubeLine>): TubeMapTrain? {
        val lineId = train.lineIds.firstOrNull() ?: return null
        val lineName = train.lineNames.firstOrNull() ?: LineName(lineId.value)
        val projectedLine = projectedLines.firstOrNull { line -> line.line.id == lineId }
        val coordinate = projectedLine?.let { line -> projectTrainCoordinate(train, line) } ?: fallbackCoordinate(train)

        return TubeMapTrain(
            train.trainId,
            train.vehicleId,
            lineId,
            lineName,
            train.direction,
            train.destinationName,
            train.towards,
            train.currentLocation,
            coordinate,
            train.secondsToNextStop,
            train.expectedArrival,
            train.observedAt
        )
    }

    private fun projectTrainCoordinate(train: LiveTubeTrain, projectedLine: ProjectedTubeLine): GeoCoordinate? {
        val location = train.location
        val betweenCoordinate = location.fromStation?.let { fromStation ->
            location.toStation?.let { toStation ->
                projectedLine.projectBetweenStations(fromStation, toStation)
            }
        }
        if (betweenCoordinate != null) {
            return betweenCoordinate
        }

        val stationCoordinate = location.station?.let { station ->
            projectedLine.projectStation(station.coordinate)
        } ?: train.nextStop?.let { nextStop ->
            projectedLine.projectStation(nextStop.coordinate)
        }
        if (stationCoordinate != null) {
            return stationCoordinate
        }

        return fallbackCoordinate(train)?.let { coordinate ->
            projectedLine.projectCoordinate(coordinate)
        }
    }

    private fun fallbackCoordinate(train: LiveTubeTrain): GeoCoordinate? =
        train.location.coordinate
            ?: train.nextStop?.coordinate
            ?: train.location.station?.coordinate
            ?: train.location.toStation?.coordinate
            ?: train.location.fromStation?.coordinate
}

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
                    }
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
    ): GeoCoordinate? =
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
            ?.let { projection ->
                val midpointDistance = (projection.fromProjection.distanceAlongPath + projection.toProjection.distanceAlongPath) / 2
                projection.path.coordinateAt(midpointDistance)
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

private fun squaredDistance(left: GeoCoordinate, right: GeoCoordinate): Double =
    (left.lat - right.lat).pow(2) + (left.lon - right.lon).pow(2)

private fun distance(left: GeoCoordinate, right: GeoCoordinate): Double =
    sqrt(squaredDistance(left, right))

private fun interpolate(startCoordinate: GeoCoordinate, endCoordinate: GeoCoordinate, fraction: Double) =
    GeoCoordinate(
        startCoordinate.lat + ((endCoordinate.lat - startCoordinate.lat) * fraction),
        startCoordinate.lon + ((endCoordinate.lon - startCoordinate.lon) * fraction)
    )
