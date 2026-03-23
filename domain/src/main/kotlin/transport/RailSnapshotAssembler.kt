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
    ): LiveRailSnapshot {
        val trains = predictions
            .filter(::isSupportedPrediction)
            .groupBy(::trainIdentityKey)
            .values
            .map { group -> buildTrain(railNetwork, group) }
            .sortedWith(
                compareBy<LiveRailTrain>(
                    { train -> train.lineNames.firstOrNull()?.value.orEmpty() },
                    { train -> train.destinationName?.value.orEmpty() },
                    { train -> train.trainId.value }
                )
            )

        return LiveRailSnapshot(
            transportSourceName,
            generatedAt,
            false,
            Duration.ZERO,
            stationsQueried,
            stationsFailed,
            stationsFailed.value > 0,
            LiveTrainCount(trains.size),
            supportedRailLineIds,
            trains
        )
    }

    private fun buildTrain(
        railNetwork: RailNetwork,
        predictions: List<RailPredictionRecord>
    ): LiveRailTrain {
        val nextStopRepresentative = predictions.minWith(nextStopPredictionComparator)
        val displayRepresentative = predictions.minWith(displayPredictionComparator)
        val boardStation = nextStopRepresentative.stationId?.let { stationId -> railNetwork.stationsById[stationId] }
        val lineIds = predictions.mapNotNull { prediction -> prediction.lineId }.distinct().sortedBy(LineId::value)
        val lineNames = predictions.mapNotNull { prediction -> prediction.lineName }.distinct().sortedBy(LineName::value)
        val location = railLocationEstimator.estimateLocation(
            displayRepresentative.currentLocation,
            boardStation
        )
        val currentLocation = displayRepresentative.currentLocation ?: location.description

        return LiveRailTrain(
            trainIdFor(nextStopRepresentative),
            nextStopRepresentative.vehicleId,
            lineIds,
            lineNames,
            nextStopRepresentative.direction ?: displayRepresentative.direction,
            nextStopRepresentative.destinationName ?: displayRepresentative.destinationName,
            nextStopRepresentative.towards ?: displayRepresentative.towards,
            currentLocation,
            location,
            boardStation?.toReference(),
            nextStopRepresentative.secondsToNextStop,
            nextStopRepresentative.expectedArrival,
            nextStopRepresentative.observedAt ?: displayRepresentative.observedAt,
            PredictionCount(predictions.size)
        )
    }

    private fun isSupportedPrediction(prediction: RailPredictionRecord): Boolean =
        prediction.lineId in supportedRailLineIds ||
            prediction.modeName in supportedRailModes

    private fun trainIdentityKey(prediction: RailPredictionRecord): String =
        prediction.vehicleId?.let { vehicleId ->
            listOf(prediction.lineId?.value.orEmpty(), vehicleId.value).joinToString("|")
        } ?: listOf(
            prediction.lineId?.value,
            prediction.stationId?.value,
            prediction.direction?.value,
            prediction.destinationName?.value,
            prediction.currentLocation?.value,
            prediction.towards?.value,
            prediction.expectedArrival?.toString()
        ).joinToString("|")

    private fun trainIdFor(prediction: RailPredictionRecord): TrainId =
        TrainId(trainIdentityKey(prediction))

    private companion object {
        val nextStopPredictionComparator = compareBy<RailPredictionRecord>(
            { prediction -> if (prediction.stationId == null) 1 else 0 },
            { prediction -> prediction.expectedArrival?.toEpochMilli() ?: Long.MAX_VALUE },
            { prediction -> prediction.secondsToNextStop?.seconds ?: Long.MAX_VALUE },
            { prediction -> if (prediction.currentLocation == null) 1 else 0 }
        )
        val displayPredictionComparator = compareBy<RailPredictionRecord>(
            { prediction -> if (prediction.currentLocation == null) 1 else 0 },
            { prediction -> if (prediction.stationId == null) 1 else 0 },
            { prediction -> prediction.expectedArrival?.toEpochMilli() ?: Long.MAX_VALUE },
            { prediction -> prediction.secondsToNextStop?.seconds ?: Long.MAX_VALUE },
            { prediction -> prediction.currentLocation?.value.orEmpty() }
        )
    }
}
