package transport

interface TubeLocationEstimator {
    fun estimateLocation(
        currentLocation: LocationDescription?,
        nextStopStation: TubeStation?
    ): LocationEstimate
}

class RealTubeLocationEstimator : TubeLocationEstimator {
    override fun estimateLocation(
        currentLocation: LocationDescription?,
        nextStopStation: TubeStation?
    ): LocationEstimate =
        nextStopAnchor(currentLocation, nextStopStation)

    private fun nextStopAnchor(
        currentLocation: LocationDescription?,
        nextStopStation: TubeStation?
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
