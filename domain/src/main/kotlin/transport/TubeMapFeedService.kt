package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface TubeMapFeedService {
    suspend fun start()
    suspend fun getTubeMap(forceRefresh: Boolean): TransportResult<TubeMapSnapshot>
    fun currentError(): TransportError?
    fun updates(): Flow<TubeMapFeedUpdate>
}

sealed interface TubeMapFeedUpdate {
    data class SnapshotUpdated(val snapshot: TubeMapSnapshot) : TubeMapFeedUpdate
    data class ErrorUpdated(val error: TransportError) : TubeMapFeedUpdate
}

class RealTubeMapFeedService(
    private val tubeMapService: TubeMapService,
    private val clock: Clock,
    private val pollInterval: Duration,
    private val coroutineScope: CoroutineScope
) : TubeMapFeedService {
    private val cachedSnapshot = AtomicReference<CachedTubeMapSnapshot?>(null)
    private val latestError = AtomicReference<TransportError?>(null)
    private val lastRefreshAttemptAt = AtomicReference<Instant?>(null)
    private val started = AtomicBoolean(false)
    private val refreshLock = Mutex()
    private val updateFlow = MutableSharedFlow<TubeMapFeedUpdate>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override suspend fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        refreshIfDue()
        coroutineScope.launch {
            while (isActive) {
                delay(pollInterval.toMillis())
                refreshIfDue()
            }
        }
    }

    override suspend fun getTubeMap(forceRefresh: Boolean): TransportResult<TubeMapSnapshot> {
        if (forceRefresh) {
            refreshIfDue()
        }

        return cachedSnapshot.get()?.let { cached ->
            Success(cached.toSnapshot(clock, true))
        } ?: latestError.get()?.let { error ->
            Failure(error)
        } ?: Failure(TransportError.SnapshotUnavailable("No cached rail map is available yet."))
    }

    override fun currentError(): TransportError? =
        latestError.get()

    override fun updates(): Flow<TubeMapFeedUpdate> =
        updateFlow.asSharedFlow()

    private suspend fun refreshIfDue() {
        refreshLock.withLock {
            val now = Instant.now(clock)
            val lastAttempt = lastRefreshAttemptAt.get()
            if (lastAttempt != null && Duration.between(lastAttempt, now) < pollInterval) {
                return
            }

            lastRefreshAttemptAt.set(now)

            when (val mapResult = tubeMapService.getTubeMap(true)) {
                is Success -> {
                    val cached = CachedTubeMapSnapshot(mapResult.value.generatedAt, mapResult.value)
                    cachedSnapshot.set(cached)
                    latestError.set(null)
                    updateFlow.tryEmit(TubeMapFeedUpdate.SnapshotUpdated(cached.toSnapshot(clock, false)))
                }
                is Failure -> {
                    latestError.set(mapResult.reason)
                    updateFlow.tryEmit(TubeMapFeedUpdate.ErrorUpdated(mapResult.reason))
                }
            }
        }
    }
}

data class CachedTubeMapSnapshot(
    val generatedAt: Instant,
    val snapshot: TubeMapSnapshot
) {
    fun toSnapshot(clock: Clock, cached: Boolean): TubeMapSnapshot {
        val cacheAge = if (cached) {
            Duration.between(generatedAt, Instant.now(clock)).let { duration ->
                if (duration.isNegative) Duration.ZERO else duration
            }
        } else {
            Duration.ZERO
        }

        return TubeMapSnapshot(
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
