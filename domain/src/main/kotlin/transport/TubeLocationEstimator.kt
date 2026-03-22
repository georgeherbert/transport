package transport

interface TubeLocationEstimator {
    fun estimateLocation(
        tubeNetwork: TubeNetwork,
        lineIds: Set<LineId>,
        currentLocation: LocationDescription?,
        boardStation: TubeStation?
    ): LocationEstimate
}

class RealTubeLocationEstimator : TubeLocationEstimator {
    override fun estimateLocation(
        tubeNetwork: TubeNetwork,
        lineIds: Set<LineId>,
        currentLocation: LocationDescription?,
        boardStation: TubeStation?
    ): LocationEstimate {
        val description = currentLocation
            ?.value
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::LocationDescription)
        if (description == null) {
            return boardFallback(boardStation)
        }

        val betweenMatch = betweenRegex.matchEntire(description.value)
        if (betweenMatch != null) {
            val fromStation = resolveStation(tubeNetwork, betweenMatch.groupValues[1], lineIds)
            val toStation = resolveStation(tubeNetwork, betweenMatch.groupValues[2], lineIds)
            return LocationEstimate(
                LocationType.BETWEEN_STATIONS,
                description,
                midpoint(fromStation, toStation),
                null,
                fromStation?.toReference(),
                toStation?.toReference()
            )
        }

        val atMatch = atRegex.matchEntire(description.value)
        if (atMatch != null) {
            return stationEstimate(LocationType.AT_STATION, description, resolveStation(tubeNetwork, atMatch.groupValues[1], lineIds))
        }

        val approachingMatch = approachingRegex.matchEntire(description.value)
        if (approachingMatch != null) {
            return stationEstimate(
                LocationType.APPROACHING_STATION,
                description,
                resolveStation(tubeNetwork, approachingMatch.groupValues[1], lineIds)
            )
        }

        val departedMatch = departedRegex.matchEntire(description.value)
        if (departedMatch != null) {
            return stationEstimate(
                LocationType.DEPARTED_STATION,
                description,
                resolveStation(tubeNetwork, departedMatch.groupValues[1], lineIds)
            )
        }

        val nearMatch = nearRegex.matchEntire(description.value)
        if (nearMatch != null) {
            return stationEstimate(LocationType.NEAR_STATION, description, resolveStation(tubeNetwork, nearMatch.groupValues[1], lineIds))
        }

        return LocationEstimate(LocationType.UNKNOWN, description, null, null, null, null)
    }

    private fun boardFallback(boardStation: TubeStation?): LocationEstimate =
        if (boardStation == null) {
            LocationEstimate(LocationType.UNKNOWN, LocationDescription("Location unavailable"), null, null, null, null)
        } else {
            LocationEstimate(
                LocationType.STATION_BOARD,
                LocationDescription(boardStation.name.value),
                boardStation.coordinate,
                boardStation.toReference(),
                null,
                null
            )
        }

    private fun stationEstimate(
        locationType: LocationType,
        description: LocationDescription,
        station: TubeStation?
    ): LocationEstimate =
        if (station == null) {
            LocationEstimate(locationType, description, null, null, null, null)
        } else {
            LocationEstimate(locationType, description, station.coordinate, station.toReference(), null, null)
        }

    private fun resolveStation(tubeNetwork: TubeNetwork, rawName: String, lineIds: Set<LineId>): TubeStation? {
        val candidates = tubeNetwork.aliases[normalizeStationName(rawName)]
        if (candidates == null || candidates.isEmpty()) {
            return null
        }

        val matchingCandidates = candidates.filter { station ->
            station.lineIds.any { lineId -> lineId in lineIds }
        }

        return if (matchingCandidates.isNotEmpty()) {
            matchingCandidates.first()
        } else {
            candidates.first()
        }
    }

    private fun midpoint(fromStation: TubeStation?, toStation: TubeStation?): GeoCoordinate? =
        if (fromStation == null || toStation == null) {
            null
        } else {
            GeoCoordinate(
                (fromStation.coordinate.lat + toStation.coordinate.lat) / 2,
                (fromStation.coordinate.lon + toStation.coordinate.lon) / 2
            )
        }

    private companion object {
        val betweenRegex = Regex("^Between (.+?) and (.+)$", RegexOption.IGNORE_CASE)
        val atRegex = Regex("^At (.+?)(?: Platform .+)?$", RegexOption.IGNORE_CASE)
        val approachingRegex = Regex("^Approaching (.+)$", RegexOption.IGNORE_CASE)
        val departedRegex = Regex("^(?:Departed|Left) (.+)$", RegexOption.IGNORE_CASE)
        val nearRegex = Regex("^Near (.+)$", RegexOption.IGNORE_CASE)
    }
}
