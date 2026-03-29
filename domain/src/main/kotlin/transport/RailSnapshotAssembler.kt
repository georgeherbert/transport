package transport

import java.time.Duration
import java.time.Instant

interface RailSnapshotAssembler {
    fun assemble(
        railNetwork: RailNetwork,
        predictions: List<RailPredictionRecord>,
        generatedAt: Instant,
        stationsQueried: StationQueryCount,
        stationsFailed: StationFailureCount
    ): LiveRailSnapshot
}

class RealRailSnapshotAssembler(
    private val railLocationEstimator: RailLocationEstimator
) : RailSnapshotAssembler {
    override fun assemble(
        railNetwork: RailNetwork,
        predictions: List<RailPredictionRecord>,
        generatedAt: Instant,
        stationsQueried: StationQueryCount,
        stationsFailed: StationFailureCount
    ): LiveRailSnapshot =
        predictions
            .filter(::isSupportedPrediction)
            .groupBy(::serviceIdentityKey)
            .values
            .map { group -> buildService(railNetwork, group) }
            .sortedWith(
                compareBy<LiveRailService>(
                    { service -> service.lineNames.firstOrNull()?.value.orEmpty() },
                    { service -> service.destinationName?.value.orEmpty() },
                    { service -> service.serviceId.value }
                )
            )
            .let { services ->
                LiveRailSnapshot(
                    transportSourceName,
                    generatedAt,
                    false,
                    Duration.ZERO,
                    stationsQueried,
                    stationsFailed,
                    stationsFailed.value > 0,
                    LiveServiceCount(services.size),
                    supportedRailLineIds,
                    services
                )
            }

    private fun buildService(
        railNetwork: RailNetwork,
        predictions: List<RailPredictionRecord>
    ): LiveRailService =
        predictions.minWith(nextStopPredictionComparator).let { nextStopRepresentative ->
            predictions.minWith(displayPredictionComparator).let { displayRepresentative ->
                val boardStation = nextStopRepresentative.stationId?.let { stationId -> railNetwork.stationsById[stationId] }
                val primaryLineId = nextStopRepresentative.lineId ?: displayRepresentative.lineId
                val primaryLineName = nextStopRepresentative.lineName ?: displayRepresentative.lineName
                val lineIds = orderedLineIds(predictions, primaryLineId)
                val lineNames = orderedLineNames(predictions, primaryLineName)
                val location = railLocationEstimator.estimateLocation(
                    displayRepresentative.currentLocation,
                    boardStation
                )
                val currentLocation = displayRepresentative.currentLocation ?: location.description

                LiveRailService(
                    serviceIdFor(nextStopRepresentative),
                    nextStopRepresentative.vehicleId,
                    lineIds,
                    lineNames,
                    nextStopRepresentative.direction ?: displayRepresentative.direction,
                    nextStopRepresentative.destinationName ?: displayRepresentative.destinationName,
                    nextStopRepresentative.towards ?: displayRepresentative.towards,
                    currentLocation,
                    location,
                    boardStation?.toReference(),
                    nextStopRepresentative.expectedArrival,
                    nextStopRepresentative.observedAt ?: displayRepresentative.observedAt,
                    PredictionCount(predictions.size),
                    futureArrivals(predictions)
                )
            }
        }

    private fun isSupportedPrediction(prediction: RailPredictionRecord): Boolean =
        prediction.lineId in supportedRailLineIds ||
            prediction.modeName in supportedRailModes

    private fun serviceIdentityKey(prediction: RailPredictionRecord): String =
        prediction.vehicleId.value

    private fun serviceIdFor(prediction: RailPredictionRecord): ServiceId =
        ServiceId(prediction.vehicleId.value)

    private fun futureArrivals(predictions: List<RailPredictionRecord>) =
        predictions
            .mapNotNull(::futureArrival)
            .groupBy(::futureArrivalKey)
            .values
            .map { arrivals -> arrivals.minBy(FutureStationArrival::expectedArrival) }
            .sortedBy(FutureStationArrival::expectedArrival)

    private fun futureArrival(prediction: RailPredictionRecord) =
        prediction.stationName
            ?.let { stationName ->
                prediction.expectedArrival?.let { expectedArrival ->
                    FutureStationArrival(
                        prediction.stationId,
                        stationName,
                        expectedArrival
                    )
                }
            }

    private fun futureArrivalKey(arrival: FutureStationArrival) =
        arrival.stationId?.value ?: arrival.stationName.value

    private fun orderedLineIds(
        predictions: List<RailPredictionRecord>,
        primaryLineId: LineId?
    ) =
        primaryLineId
            ?.let { currentLineId ->
                listOf(currentLineId) + predictions
                    .mapNotNull(RailPredictionRecord::lineId)
                    .filter { lineId -> lineId != currentLineId }
                    .distinctBy(LineId::value)
                    .sortedBy(LineId::value)
            }
            ?: predictions
                .mapNotNull(RailPredictionRecord::lineId)
                .distinctBy(LineId::value)
                .sortedBy(LineId::value)

    private fun orderedLineNames(
        predictions: List<RailPredictionRecord>,
        primaryLineName: LineName?
    ) =
        primaryLineName
            ?.let { currentLineName ->
                listOf(currentLineName) + predictions
                    .mapNotNull(RailPredictionRecord::lineName)
                    .filter { lineName -> lineName != currentLineName }
                    .distinctBy(LineName::value)
                    .sortedBy(LineName::value)
            }
            ?: predictions
                .mapNotNull(RailPredictionRecord::lineName)
                .distinctBy(LineName::value)
                .sortedBy(LineName::value)

    private companion object {
        val nextStopPredictionComparator = compareBy<RailPredictionRecord>(
            { prediction -> if (prediction.stationId == null) 1 else 0 },
            { prediction -> prediction.expectedArrival?.toEpochMilli() ?: Long.MAX_VALUE },
            { prediction -> if (prediction.currentLocation == null) 1 else 0 }
        )
        val displayPredictionComparator = compareBy<RailPredictionRecord>(
            { prediction -> if (prediction.currentLocation == null) 1 else 0 },
            { prediction -> if (prediction.stationId == null) 1 else 0 },
            { prediction -> prediction.expectedArrival?.toEpochMilli() ?: Long.MAX_VALUE },
            { prediction -> prediction.currentLocation?.value.orEmpty() }
        )
    }
}
