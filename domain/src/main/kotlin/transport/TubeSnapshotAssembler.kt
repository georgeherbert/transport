package transport

import java.time.Duration
import java.time.Instant

interface TubeSnapshotAssembler {
    fun assemble(
        tubeNetwork: TubeNetwork,
        predictions: List<TubePredictionRecord>,
        generatedAt: Instant,
        stationsQueried: StationQueryCount,
        stationsFailed: StationFailureCount
    ): LiveTubeSnapshot
}

class RealTubeSnapshotAssembler(
    private val tubeLocationEstimator: TubeLocationEstimator
) : TubeSnapshotAssembler {
    override fun assemble(
        tubeNetwork: TubeNetwork,
        predictions: List<TubePredictionRecord>,
        generatedAt: Instant,
        stationsQueried: StationQueryCount,
        stationsFailed: StationFailureCount
    ): LiveTubeSnapshot {
        val trains = predictions
            .filter(::isSupportedPrediction)
            .groupBy(::trainIdentityKey)
            .values
            .map { group -> buildTrain(tubeNetwork, group) }
            .sortedWith(
                compareBy<LiveTubeTrain>(
                    { train -> train.lineNames.firstOrNull()?.value.orEmpty() },
                    { train -> train.destinationName?.value.orEmpty() },
                    { train -> train.trainId.value }
                )
            )

        return LiveTubeSnapshot(
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
        tubeNetwork: TubeNetwork,
        predictions: List<TubePredictionRecord>
    ): LiveTubeTrain {
        val nextStopRepresentative = predictions.minWith(nextStopPredictionComparator)
        val displayRepresentative = predictions.minWith(displayPredictionComparator)
        val boardStation = nextStopRepresentative.stationId?.let { stationId -> tubeNetwork.stationsById[stationId] }
        val lineIds = predictions.mapNotNull { prediction -> prediction.lineId }.distinct().sortedBy(LineId::value)
        val lineNames = predictions.mapNotNull { prediction -> prediction.lineName }.distinct().sortedBy(LineName::value)
        val location = tubeLocationEstimator.estimateLocation(
            tubeNetwork,
            lineIds.toSet(),
            displayRepresentative.currentLocation,
            boardStation
        )
        val currentLocation = displayRepresentative.currentLocation ?: location.description

        return LiveTubeTrain(
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

    private fun isSupportedPrediction(prediction: TubePredictionRecord): Boolean =
        prediction.lineId in supportedRailLineIds ||
            prediction.modeName in supportedRailModes

    private fun trainIdentityKey(prediction: TubePredictionRecord): String =
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

    private fun trainIdFor(prediction: TubePredictionRecord): TrainId =
        TrainId(trainIdentityKey(prediction))

    private companion object {
        val nextStopPredictionComparator = compareBy<TubePredictionRecord>(
            { prediction -> if (prediction.stationId == null) 1 else 0 },
            { prediction -> prediction.expectedArrival?.toEpochMilli() ?: Long.MAX_VALUE },
            { prediction -> prediction.secondsToNextStop?.seconds ?: Long.MAX_VALUE },
            { prediction -> if (prediction.currentLocation == null) 1 else 0 }
        )
        val displayPredictionComparator = compareBy<TubePredictionRecord>(
            { prediction -> if (prediction.currentLocation == null) 1 else 0 },
            { prediction -> if (prediction.stationId == null) 1 else 0 },
            { prediction -> prediction.expectedArrival?.toEpochMilli() ?: Long.MAX_VALUE },
            { prediction -> prediction.secondsToNextStop?.seconds ?: Long.MAX_VALUE },
            { prediction -> prediction.currentLocation?.value.orEmpty() }
        )
    }
}
