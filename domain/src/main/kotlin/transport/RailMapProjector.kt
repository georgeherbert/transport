package transport

import kotlin.math.abs
import kotlin.math.pow

interface RailMapProjector {
    fun project(snapshot: LiveRailSnapshot, lineMap: RailLineMap): RailMapSnapshot
}

interface RailPathSmoother {
    fun smooth(lineMap: RailLineMap): RailLineMap
}

class RealRailMapProjector(
    private val railPathSmoother: RailPathSmoother,
    private val railLineProjectionFactory: RailLineProjectionFactory
) : RailMapProjector {
    override fun project(snapshot: LiveRailSnapshot, lineMap: RailLineMap) =
        railPathSmoother.smooth(lineMap).let { smoothedLineMap ->
            val projectedLines = smoothedLineMap.lines.associate { line ->
                line.id to railLineProjectionFactory.create(line)
            }

            RailMapSnapshot(
                snapshot.generatedAt,
                snapshot.stationsFailed,
                snapshot.partial,
                snapshot.serviceCount,
                smoothedLineMap.lines,
                projectStations(snapshot, smoothedLineMap, projectedLines),
                snapshot.services.mapNotNull { service -> projectTrain(service, projectedLines) }
            )
        }

    private fun projectStations(
        snapshot: LiveRailSnapshot,
        lineMap: RailLineMap,
        projectedLines: Map<LineId, RailLineProjection>
    ): List<MapStation> =
        stationArrivalsByStationId(snapshot)
            .let { arrivalsByStationId ->
                lineMap.lines
                    .filter { line -> line.id in supportedMapStationLineIdSet }
                    .flatMap { line ->
                        projectedLines[line.id]?.let { projectedLine ->
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
                        } ?: emptyList()
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
                                .sortedBy(LineId::value),
                            arrivalsByStationId[firstCandidate.id] ?: emptyList()
                        )
                    }
                    .sortedBy { station -> station.name.value }
            }

    private fun stationArrivalsByStationId(snapshot: LiveRailSnapshot) =
        snapshot.services
            .flatMap { service ->
                service.lineIds.firstOrNull()?.let { lineId ->
                    service.futureArrivals.mapNotNull { arrival ->
                        arrival.stationId?.let { stationId ->
                            stationId to StationArrival(
                                service.serviceId,
                                lineId,
                                service.destinationName,
                                arrival.expectedArrival
                            )
                        }
                    }
                } ?: emptyList()
            }
            .groupBy({ entry -> entry.first }, { entry -> entry.second })
            .mapValues { entry ->
                entry.value.sortedWith(
                    compareBy<StationArrival>(
                        StationArrival::expectedArrival,
                        { arrival -> arrival.lineId.value },
                        { arrival -> arrival.destinationName?.value.orEmpty() },
                        { arrival -> arrival.serviceId.value }
                    )
                )
            }

    private fun projectTrain(
        service: LiveRailService,
        projectedLines: Map<LineId, RailLineProjection>
    ) =
        service.lineIds.firstOrNull()?.let { lineId ->
            val lineName = service.lineNames.firstOrNull() ?: LineName(lineId.value)
            val projection = projectedLines[lineId]?.projectNextStopAnchor(service)

            RailMapService(
                service.serviceId,
                lineId,
                lineName,
                service.direction,
                service.destinationName,
                service.towards,
                service.currentLocation,
                service.nextStop,
                projection?.coordinate,
                projection?.heading,
                service.expectedArrival,
                service.futureArrivals
            )
        }
}

class RealIdentityRailPathSmoother : RailPathSmoother {
    override fun smooth(lineMap: RailLineMap) =
        lineMap
}

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

    private fun smoothCoordinates(coordinates: List<GeoCoordinate>): List<GeoCoordinate> =
        if (coordinates.size < 3) {
            coordinates
        } else {
            val smoothed = mutableListOf<GeoCoordinate>()
            smoothed += coordinates.first()

            for (index in 0 until coordinates.lastIndex) {
                val previousPoint = coordinates.getOrElse(index - 1) { coordinates[index] }
                val currentPoint = coordinates[index]
                val nextPoint = coordinates[index + 1]
                val followingPoint = coordinates.getOrElse(index + 2) { coordinates[index + 1] }

                for (step in 1 until samplesPerSegment) {
                    val t = step.toDouble() / samplesPerSegment.toDouble()
                    smoothed += catmullRomPoint(previousPoint, currentPoint, nextPoint, followingPoint, t)
                }

                smoothed += nextPoint
            }

            smoothed
        }

    private fun catmullRomPoint(
        previousPoint: GeoCoordinate,
        currentPoint: GeoCoordinate,
        nextPoint: GeoCoordinate,
        followingPoint: GeoCoordinate,
        t: Double
    ): GeoCoordinate =
        0.0.let { startParameter ->
            val currentPointParameter = nextParameter(startParameter, previousPoint, currentPoint)
            val nextPointParameter = nextParameter(currentPointParameter, currentPoint, nextPoint)
            val followingPointParameter = nextParameter(nextPointParameter, nextPoint, followingPoint)
            val pointParameter = currentPointParameter + ((nextPointParameter - currentPointParameter) * t)

            val firstInterpolation = interpolateByParameter(previousPoint, currentPoint, startParameter, currentPointParameter, pointParameter)
            val secondInterpolation = interpolateByParameter(currentPoint, nextPoint, currentPointParameter, nextPointParameter, pointParameter)
            val thirdInterpolation = interpolateByParameter(nextPoint, followingPoint, nextPointParameter, followingPointParameter, pointParameter)
            val fourthInterpolation = interpolateByParameter(firstInterpolation, secondInterpolation, startParameter, nextPointParameter, pointParameter)
            val fifthInterpolation = interpolateByParameter(secondInterpolation, thirdInterpolation, currentPointParameter, followingPointParameter, pointParameter)

            interpolateByParameter(fourthInterpolation, fifthInterpolation, currentPointParameter, nextPointParameter, pointParameter)
        }

    private fun nextParameter(
        currentParameter: Double,
        currentPoint: GeoCoordinate,
        nextPoint: GeoCoordinate
    ) =
        currentParameter + distance(currentPoint, nextPoint).pow(0.5)

    private fun interpolateByParameter(
        startCoordinate: GeoCoordinate,
        endCoordinate: GeoCoordinate,
        startParameter: Double,
        endParameter: Double,
        currentParameter: Double
    ): GeoCoordinate =
        if (abs(endParameter - startParameter) < 0.0000001) {
            endCoordinate
        } else {
            val fraction = (currentParameter - startParameter) / (endParameter - startParameter)
            interpolate(startCoordinate, endCoordinate, fraction)
        }
}

private data class ProjectedStationCandidate(
    val id: StationId,
    val name: StationName,
    val sourceCoordinate: GeoCoordinate,
    val projectedCoordinate: GeoCoordinate,
    val lineId: LineId
)
