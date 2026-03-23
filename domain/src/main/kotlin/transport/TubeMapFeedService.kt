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
    data class TrainPositionsUpdated(val trainPositions: TubeMapTrainPositions) : TubeMapFeedUpdate
    data class ErrorUpdated(val error: TransportError) : TubeMapFeedUpdate
}

class RealTubeMapFeedService(
    private val tubeMapService: TubeMapService,
    private val tubeMapMotionEngine: TubeMapMotionEngine,
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
        coroutineScope.launch {
            while (isActive) {
                delay(animationInterval.toMillis())
                emitAnimatedSnapshotIfAvailable()
            }
        }
    }

    override suspend fun getTubeMap(forceRefresh: Boolean): TransportResult<TubeMapSnapshot> {
        if (forceRefresh) {
            refreshIfDue()
        }

        return cachedSnapshot.get()?.let { cached ->
            Success(cached.toSnapshot(clock, true, tubeMapMotionEngine))
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
                    val observedSnapshot = tubeMapMotionEngine.observe(mapResult.value)
                    val cached = CachedTubeMapSnapshot(observedSnapshot.generatedAt, observedSnapshot)
                    cachedSnapshot.set(cached)
                    latestError.set(null)
                    updateFlow.tryEmit(TubeMapFeedUpdate.SnapshotUpdated(observedSnapshot))
                }
                is Failure -> {
                    latestError.set(mapResult.reason)
                    updateFlow.tryEmit(TubeMapFeedUpdate.ErrorUpdated(mapResult.reason))
                }
            }
        }
    }

    private fun emitAnimatedSnapshotIfAvailable() {
        if (latestError.get() != null) {
            return
        }

        cachedSnapshot.get()?.let { cached ->
            val currentTime = Instant.now(clock)
            val animatedSnapshot = tubeMapMotionEngine.advance(cached.snapshot, currentTime)
            if (animatedSnapshot.trains == cached.snapshot.trains) {
                return
            }

            updateFlow.tryEmit(
                TubeMapFeedUpdate.TrainPositionsUpdated(
                    cached.toTrainPositions(true, currentTime, animatedSnapshot)
                )
            )
        }
    }

    private companion object {
        val animationInterval: Duration = Duration.ofMillis(250)
    }
}

data class CachedTubeMapSnapshot(
    val generatedAt: Instant,
    val snapshot: TubeMapSnapshot
) {
    fun toSnapshot(
        clock: Clock,
        cached: Boolean,
        tubeMapMotionEngine: TubeMapMotionEngine
    ) =
        toSnapshot(clock, cached, tubeMapMotionEngine, Instant.now(clock))

    fun toSnapshot(
        clock: Clock,
        cached: Boolean,
        tubeMapMotionEngine: TubeMapMotionEngine,
        currentTime: Instant
    ): TubeMapSnapshot {
        val animatedSnapshot = tubeMapMotionEngine.advance(snapshot, currentTime)
        val cacheAge = if (cached) {
            Duration.between(generatedAt, currentTime).let { duration ->
                if (duration.isNegative) Duration.ZERO else duration
            }
        } else {
            Duration.ZERO
        }

        return TubeMapSnapshot(
            animatedSnapshot.source,
            animatedSnapshot.generatedAt,
            cached,
            cacheAge,
            animatedSnapshot.stationsQueried,
            animatedSnapshot.stationsFailed,
            animatedSnapshot.partial,
            animatedSnapshot.trainCount,
            animatedSnapshot.lines,
            animatedSnapshot.stations,
            animatedSnapshot.trains
        )
    }

    fun toTrainPositions(
        cached: Boolean,
        currentTime: Instant,
        animatedSnapshot: TubeMapSnapshot
    ): TubeMapTrainPositions {
        val cacheAge = if (cached) {
            Duration.between(generatedAt, currentTime).let { duration ->
                if (duration.isNegative) Duration.ZERO else duration
            }
        } else {
            Duration.ZERO
        }

        return TubeMapTrainPositions(
            animatedSnapshot.source,
            animatedSnapshot.generatedAt,
            cached,
            cacheAge,
            animatedSnapshot.stationsQueried,
            animatedSnapshot.stationsFailed,
            animatedSnapshot.partial,
            animatedSnapshot.trainCount,
            animatedSnapshot.trains
        )
    }
}
