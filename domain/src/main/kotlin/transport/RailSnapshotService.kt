package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.map
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface RailSnapshotService {
    suspend fun refreshLiveSnapshot(): TransportResult<LiveRailSnapshot>
}

class RealRailSnapshotService(
    private val railData: RailData,
    private val railMetadataRepository: RailMetadataRepository,
    private val railSnapshotAssembler: RailSnapshotAssembler,
    private val clock: Clock
) : RailSnapshotService {
    private val cachedSnapshot = AtomicReference<LiveRailSnapshot?>(null)
    private val refreshLock = Mutex()

    override suspend fun refreshLiveSnapshot() =
        refreshSnapshot()

    private suspend fun refreshSnapshot(): TransportResult<LiveRailSnapshot> =
        refreshLock.withLock {
            loadSnapshot(cachedSnapshot.get())
        }

    private suspend fun loadSnapshot(latestSnapshot: LiveRailSnapshot?): TransportResult<LiveRailSnapshot> =
        when (val networkResult = railMetadataRepository.getRailNetwork()) {
            is Success -> refreshFromNetwork(networkResult.value, latestSnapshot)
            is Failure -> latestSnapshot?.let(::Success) ?: Failure(networkResult.reason)
        }

    private suspend fun refreshFromNetwork(
        railNetwork: RailNetwork,
        latestSnapshot: LiveRailSnapshot?
    ): TransportResult<LiveRailSnapshot> =
        when (val predictionBatchResult = fetchPredictions()) {
            is Success -> {
                val generatedAt = clock.instant()
                val snapshot = railSnapshotAssembler.assemble(
                    railNetwork,
                    predictionBatchResult.value,
                    generatedAt
                )
                cachedSnapshot.set(snapshot)
                Success(snapshot)
            }
            is Failure -> latestSnapshot?.let(::Success) ?: Failure(predictionBatchResult.reason)
        }

    private suspend fun fetchPredictions(): TransportResult<List<RailPredictionRecord>> =
        supportedRailModes
            .map { mode -> railData.fetchPredictions(mode) }
            .failFast()
            .map(List<List<RailPredictionRecord>>::flatten)
            .let { bulkPredictionResult ->
                when (bulkPredictionResult) {
                    is Success -> Success(bulkPredictionResult.value)
                    is Failure ->
                        Failure(TransportError.SnapshotUnavailable(describeTransportError(bulkPredictionResult.reason)))
                }
            }
}
