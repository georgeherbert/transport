package transport

interface RailLocationEstimator {
    fun estimateLocation(
        currentLocation: LocationDescription?,
        nextStopStation: RailStation?
    ): LocationEstimate
}

class RealRailLocationEstimator : RailLocationEstimator {
    override fun estimateLocation(
        currentLocation: LocationDescription?,
        nextStopStation: RailStation?
    ): LocationEstimate =
        nextStopAnchor(currentLocation, nextStopStation)

    private fun nextStopAnchor(
        currentLocation: LocationDescription?,
        nextStopStation: RailStation?
    ): LocationEstimate {
        val description = currentLocation
            ?.value
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::LocationDescription)

        return if (nextStopStation == null) {
            LocationEstimate(
                LocationType.UNKNOWN,
                description ?: LocationDescription("Location unavailable"),
                null,
                null
            )
        } else {
            LocationEstimate(
                LocationType.STATION_BOARD,
                description ?: LocationDescription(nextStopStation.name.value),
                nextStopStation.coordinate,
                nextStopStation.toReference()
            )
        }
    }
}
