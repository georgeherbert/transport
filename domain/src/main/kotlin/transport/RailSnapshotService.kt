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

interface RailSnapshotService {
    suspend fun getLiveSnapshot(forceRefresh: Boolean): TransportResult<LiveRailSnapshot>
}

class RealRailSnapshotService(
    private val railData: RailData,
    private val railMetadataRepository: RailMetadataRepository,
    private val railSnapshotAssembler: RailSnapshotAssembler,
    private val clock: Clock,
    private val cacheTtl: Duration
) : RailSnapshotService {
    private val cachedSnapshot = AtomicReference<CachedLiveRailSnapshot?>(null)
    private val refreshLock = Mutex()

    override suspend fun getLiveSnapshot(forceRefresh: Boolean) =
        cachedSnapshot.get().let { currentSnapshot ->
            if (!forceRefresh && currentSnapshot != null && !currentSnapshot.isExpired(clock, cacheTtl)) {
                Success(currentSnapshot.snapshot)
            } else {
                refreshSnapshot(forceRefresh)
            }
        }

    private suspend fun refreshSnapshot(forceRefresh: Boolean): TransportResult<LiveRailSnapshot> =
        refreshLock.withLock {
            val latestSnapshot = cachedSnapshot.get()
            if (!forceRefresh && latestSnapshot != null && !latestSnapshot.isExpired(clock, cacheTtl)) {
                Success(latestSnapshot.snapshot)
            } else {
                when (val networkResult = railMetadataRepository.getRailNetwork()) {
                    is Success -> refreshFromNetwork(networkResult.value, latestSnapshot)
                    is Failure -> latestSnapshot?.let { cached -> Success(cached.snapshot) } ?: Failure(networkResult.reason)
                }
            }
        }

    private suspend fun refreshFromNetwork(
        railNetwork: RailNetwork,
        latestSnapshot: CachedLiveRailSnapshot?
    ): TransportResult<LiveRailSnapshot> =
        when (val predictionBatchResult = fetchPredictionBatch()) {
            is Success -> {
                val generatedAt = clock.instant()
                val snapshot = railSnapshotAssembler.assemble(
                    railNetwork,
                    predictionBatchResult.value.predictions,
                    generatedAt,
                    predictionBatchResult.value.stationsFailed
                )
                val cached = CachedLiveRailSnapshot(snapshot)
                cachedSnapshot.set(cached)
                Success(cached.snapshot)
            }
            is Failure -> latestSnapshot?.let { cached -> Success(cached.snapshot) } ?: Failure(predictionBatchResult.reason)
        }

    private suspend fun fetchPredictionBatch(): TransportResult<PredictionBatch> =
        supportedRailModes
            .map { mode -> railData.fetchPredictions(mode) }
            .failFast()
            .map(List<List<RailPredictionRecord>>::flatten)
            .let { bulkPredictionResult ->
                when (bulkPredictionResult) {
                    is Success ->
                        Success(
                            PredictionBatch(
                                bulkPredictionResult.value,
                                StationFailureCount(0)
                            )
                        )
                    is Failure ->
                        Failure(TransportError.SnapshotUnavailable(describeTransportError(bulkPredictionResult.reason)))
                }
            }
}

data class PredictionBatch(
    val predictions: List<RailPredictionRecord>,
    val stationsFailed: StationFailureCount
)

data class CachedLiveRailSnapshot(
    val snapshot: LiveRailSnapshot
) {
    fun isExpired(clock: Clock, ttl: Duration): Boolean =
        Duration.between(snapshot.generatedAt, clock.instant()) > ttl
}
