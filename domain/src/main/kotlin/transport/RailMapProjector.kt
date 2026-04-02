package transport

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

private data class ProjectedStationCandidate(
    val id: StationId,
    val name: StationName,
    val sourceCoordinate: GeoCoordinate,
    val projectedCoordinate: GeoCoordinate,
    val lineId: LineId
)
