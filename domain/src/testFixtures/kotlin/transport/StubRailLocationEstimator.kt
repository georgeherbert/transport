package transport

class StubRailLocationEstimator : RailLocationEstimator {
    private var defaultResult: LocationEstimate? = null
    private val stationResults = mutableMapOf<StationId, LocationEstimate>()

    val requests = mutableListOf<LocationEstimateRequest>()

    fun returns(locationEstimate: LocationEstimate) {
        defaultResult = locationEstimate
    }

    fun returnsFor(
        stationId: StationId,
        locationEstimate: LocationEstimate
    ) {
        stationResults[stationId] = locationEstimate
    }

    override fun estimateLocation(
        currentLocation: LocationDescription?,
        nextStopStation: RailStation?
    ) =
        run {
            requests += LocationEstimateRequest(currentLocation, nextStopStation)
            nextStopStation
                ?.id
                ?.let(stationResults::get)
                ?: defaultResult
                ?: defaultEstimate(currentLocation, nextStopStation)
        }

    private fun defaultEstimate(
        currentLocation: LocationDescription?,
        nextStopStation: RailStation?
    ) =
        nextStopStation
            ?.let { station ->
                LocationEstimate(
                    LocationType.STATION_BOARD,
                    currentLocation ?: LocationDescription(station.name.value),
                    station.coordinate,
                    station.toReference()
                )
            }
            ?: LocationEstimate(
                LocationType.UNKNOWN,
                currentLocation ?: LocationDescription("Location unavailable"),
                null,
                null
            )
}

data class LocationEstimateRequest(
    val currentLocation: LocationDescription?,
    val nextStopStation: RailStation?
)
