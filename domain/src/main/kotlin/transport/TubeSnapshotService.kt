package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.map
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface TubeSnapshotService {
    suspend fun getLiveSnapshot(forceRefresh: Boolean): TransportResult<LiveTubeSnapshot>
}

class RealTubeSnapshotService(
    private val tubeData: TubeData,
    private val tubeMetadataRepository: TubeMetadataRepository,
    private val tubeSnapshotAssembler: TubeSnapshotAssembler,
    private val clock: Clock,
    private val cacheTtl: Duration
) : TubeSnapshotService {
    private val cachedSnapshot = AtomicReference<CachedLiveTubeSnapshot?>(null)
    private val refreshLock = Mutex()

    override suspend fun getLiveSnapshot(forceRefresh: Boolean): TransportResult<LiveTubeSnapshot> {
        val currentSnapshot = cachedSnapshot.get()
        if (!forceRefresh && currentSnapshot != null && !currentSnapshot.isExpired(clock, cacheTtl)) {
            return Success(currentSnapshot.toSnapshot(clock, true))
        }
        return refreshSnapshot(forceRefresh)
    }

    private suspend fun refreshSnapshot(forceRefresh: Boolean): TransportResult<LiveTubeSnapshot> =
        refreshLock.withLock {
            val latestSnapshot = cachedSnapshot.get()
            if (!forceRefresh && latestSnapshot != null && !latestSnapshot.isExpired(clock, cacheTtl)) {
                return@withLock Success(latestSnapshot.toSnapshot(clock, true))
            }

            when (val networkResult = tubeMetadataRepository.getTubeNetwork()) {
                is Success -> refreshFromNetwork(networkResult.value, latestSnapshot)
                is Failure -> latestSnapshot?.let { cached -> Success(cached.toSnapshot(clock, true)) } ?: Failure(networkResult.reason)
            }
        }

    private suspend fun refreshFromNetwork(
        tubeNetwork: TubeNetwork,
        latestSnapshot: CachedLiveTubeSnapshot?
    ): TransportResult<LiveTubeSnapshot> =
        when (val predictionBatchResult = fetchPredictionBatch(tubeNetwork)) {
            is Success -> {
                val generatedAt = Instant.now(clock)
                val snapshot = tubeSnapshotAssembler.assemble(
                    tubeNetwork,
                    predictionBatchResult.value.predictions,
                    generatedAt,
                    predictionBatchResult.value.stationsQueried,
                    predictionBatchResult.value.stationsFailed
                )
                val cached = CachedLiveTubeSnapshot(generatedAt, snapshot)
                cachedSnapshot.set(cached)
                Success(cached.toSnapshot(clock, false))
            }
            is Failure -> latestSnapshot?.let { cached -> Success(cached.toSnapshot(clock, true)) } ?: Failure(predictionBatchResult.reason)
        }

    private suspend fun fetchPredictionBatch(tubeNetwork: TubeNetwork): TransportResult<PredictionBatch> {
        val bulkPredictionResult = supportedRailModes
            .map { mode -> tubeData.fetchPredictions(mode) }
            .failFast()
            .map(List<List<TubePredictionRecord>>::flatten)

        return when (bulkPredictionResult) {
            is Success ->
                enrichPredictions(bulkPredictionResult.value)
                    .map { predictions ->
                        PredictionBatch(
                            predictions,
                            StationQueryCount(tubeNetwork.stationsById.size),
                            StationFailureCount(0)
                        )
                    }
                    .flatMapFailure()
            is Failure ->
                Failure(TransportError.SnapshotUnavailable(describeTransportError(bulkPredictionResult.reason)))
        }
    }

    private suspend fun enrichPredictions(
        predictions: List<TubePredictionRecord>
    ): TransportResult<List<TubePredictionRecord>> {
        val vehicleIds = predictions
            .mapNotNull { prediction -> prediction.vehicleId }
            .distinct()

        if (vehicleIds.isEmpty()) {
            return Success(predictions)
        }

        return vehicleIds
            .chunked(vehiclePredictionBatchSize)
            .map { batch -> tubeData.fetchVehiclePredictions(batch) }
            .failFast()
            .map(List<List<TubePredictionRecord>>::flatten)
            .map { vehiclePredictions -> predictions.mergeVehiclePredictions(vehiclePredictions) }
    }

    private fun List<TubePredictionRecord>.mergeVehiclePredictions(
        vehiclePredictions: List<TubePredictionRecord>
    ): List<TubePredictionRecord> {
        val vehiclePredictionsByKey = vehiclePredictions.associateBy(::predictionIdentityKey)

        return map { prediction ->
            val predictionKey = predictionIdentityKey(prediction)
            val vehiclePrediction = predictionKey?.let { key -> vehiclePredictionsByKey[key] }
            if (vehiclePrediction?.secondsToNextStop == null) {
                prediction
            } else {
                prediction.copy(secondsToNextStop = vehiclePrediction.secondsToNextStop)
            }
        }
    }

    private fun predictionIdentityKey(prediction: TubePredictionRecord): PredictionIdentityKey? {
        val vehicleId = prediction.vehicleId ?: return null
        val stationId = prediction.stationId ?: return null

        return PredictionIdentityKey(
            vehicleId,
            stationId
        )
    }

private fun TransportResult<PredictionBatch>.flatMapFailure() =
    when (this) {
        is Success -> this
        is Failure -> Failure(TransportError.SnapshotUnavailable(describeTransportError(reason)))
    }
}

private data class PredictionIdentityKey(
    val vehicleId: VehicleId,
    val stationId: StationId
)

private val vehiclePredictionBatchSize = 15

data class PredictionBatch(
    val predictions: List<TubePredictionRecord>,
    val stationsQueried: StationQueryCount,
    val stationsFailed: StationFailureCount
)

data class CachedLiveTubeSnapshot(
    val generatedAt: Instant,
    val snapshot: LiveTubeSnapshot
) {
    fun isExpired(clock: Clock, ttl: Duration): Boolean =
        Duration.between(generatedAt, Instant.now(clock)) > ttl

    fun toSnapshot(clock: Clock, cached: Boolean): LiveTubeSnapshot {
        val cacheAge = if (cached) {
            Duration.between(generatedAt, Instant.now(clock)).let { duration ->
                if (duration.isNegative) Duration.ZERO else duration
            }
        } else {
            Duration.ZERO
        }

        return LiveTubeSnapshot(
            snapshot.source,
            snapshot.generatedAt,
            cached,
            cacheAge,
            snapshot.stationsQueried,
            snapshot.stationsFailed,
            snapshot.partial,
            snapshot.trainCount,
            snapshot.lines,
            snapshot.trains
        )
    }
}
