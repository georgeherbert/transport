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
            .map { group -> buildTrain(tubeNetwork, group, generatedAt) }
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
        predictions: List<TubePredictionRecord>,
        generatedAt: Instant
    ): LiveTubeTrain {
        val representative = predictions.minWith(predictionComparator)
        val boardStation = representative.stationId?.let { stationId -> tubeNetwork.stationsById[stationId] }
        val lineIds = predictions.mapNotNull { prediction -> prediction.lineId }.distinct().sortedBy(LineId::value)
        val lineNames = predictions.mapNotNull { prediction -> prediction.lineName }.distinct().sortedBy(LineName::value)
        val location = tubeLocationEstimator.estimateLocation(
            tubeNetwork,
            lineIds.toSet(),
            representative.currentLocation,
            boardStation
        )
        val currentLocation = representative.currentLocation ?: location.description

        return LiveTubeTrain(
            trainIdFor(representative),
            representative.vehicleId,
            lineIds,
            lineNames,
            representative.direction,
            representative.destinationName,
            representative.towards,
            currentLocation,
            location,
            boardStation?.toReference(),
            secondsToNextStopFor(representative, generatedAt),
            representative.expectedArrival,
            representative.observedAt,
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

    private fun secondsToNextStopFor(
        prediction: TubePredictionRecord,
        generatedAt: Instant
    ): Duration? =
        prediction.secondsToNextStop ?: prediction.expectedArrival?.let { expectedArrival ->
            val remaining = Duration.between(generatedAt, expectedArrival)
            if (remaining.isNegative) Duration.ZERO else remaining
        }

    private companion object {
        val predictionComparator = compareBy<TubePredictionRecord>(
            { prediction -> if (prediction.currentLocation == null) 1 else 0 },
            { prediction -> prediction.secondsToNextStop?.seconds ?: Long.MAX_VALUE },
            { prediction -> prediction.expectedArrival?.toString().orEmpty() }
        )
    }
}
