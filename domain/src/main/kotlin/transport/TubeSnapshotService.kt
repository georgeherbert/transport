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

    private suspend fun fetchPredictionBatch(tubeNetwork: TubeNetwork): TransportResult<PredictionBatch> =
        supportedRailModes
            .map { mode -> tubeData.fetchPredictions(mode) }
            .failFast()
            .map(List<List<TubePredictionRecord>>::flatten)
            .map { predictions ->
                PredictionBatch(
                    predictions,
                    StationQueryCount(tubeNetwork.stationsById.size),
                    StationFailureCount(0)
                )
            }
            .flatMapFailure()

private fun TransportResult<PredictionBatch>.flatMapFailure() =
    when (this) {
        is Success -> this
        is Failure -> Failure(TransportError.SnapshotUnavailable(describeTransportError(reason)))
    }
}

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
