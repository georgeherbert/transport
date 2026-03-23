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

interface RailMapFeedService {
    suspend fun start()
    suspend fun getRailMap(forceRefresh: Boolean): TransportResult<RailMapSnapshot>
    fun currentError(): TransportError?
    fun updates(): Flow<RailMapFeedUpdate>
}

sealed interface RailMapFeedUpdate {
    data class SnapshotUpdated(val snapshot: RailMapSnapshot) : RailMapFeedUpdate
    data class TrainPositionsUpdated(val trainPositions: RailMapTrainPositions) : RailMapFeedUpdate
    data class ErrorUpdated(val error: TransportError) : RailMapFeedUpdate
}

class RealRailMapFeedService(
    private val railMapService: RailMapService,
    private val railMapMotionEngine: RailMapMotionEngine,
    private val clock: Clock,
    private val pollInterval: Duration,
    private val coroutineScope: CoroutineScope
) : RailMapFeedService {
    private val cachedSnapshot = AtomicReference<CachedRailMapSnapshot?>(null)
    private val latestError = AtomicReference<TransportError?>(null)
    private val lastRefreshAttemptAt = AtomicReference<Instant?>(null)
    private val started = AtomicBoolean(false)
    private val refreshLock = Mutex()
    private val updateFlow = MutableSharedFlow<RailMapFeedUpdate>(
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

    override suspend fun getRailMap(forceRefresh: Boolean): TransportResult<RailMapSnapshot> {
        if (forceRefresh) {
            refreshIfDue()
        }

        return cachedSnapshot.get()?.let { cached ->
            Success(cached.toSnapshot(clock, true, railMapMotionEngine))
        } ?: latestError.get()?.let { error ->
            Failure(error)
        } ?: Failure(TransportError.SnapshotUnavailable("No cached rail map is available yet."))
    }

    override fun currentError(): TransportError? =
        latestError.get()

    override fun updates(): Flow<RailMapFeedUpdate> =
        updateFlow.asSharedFlow()

    private suspend fun refreshIfDue() {
        refreshLock.withLock {
            val now = Instant.now(clock)
            val lastAttempt = lastRefreshAttemptAt.get()
            if (lastAttempt != null && Duration.between(lastAttempt, now) < pollInterval) {
                return
            }

            lastRefreshAttemptAt.set(now)

            when (val mapResult = railMapService.getRailMap(true)) {
                is Success -> {
                    val observedSnapshot = railMapMotionEngine.observe(mapResult.value)
                    val cached = CachedRailMapSnapshot(observedSnapshot.generatedAt, observedSnapshot)
                    cachedSnapshot.set(cached)
                    latestError.set(null)
                    updateFlow.tryEmit(RailMapFeedUpdate.SnapshotUpdated(observedSnapshot))
                }
                is Failure -> {
                    latestError.set(mapResult.reason)
                    updateFlow.tryEmit(RailMapFeedUpdate.ErrorUpdated(mapResult.reason))
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
            val animatedSnapshot = railMapMotionEngine.advance(cached.snapshot, currentTime)
            if (animatedSnapshot.trains == cached.snapshot.trains) {
                return
            }

            updateFlow.tryEmit(
                RailMapFeedUpdate.TrainPositionsUpdated(
                    cached.toTrainPositions(true, currentTime, animatedSnapshot)
                )
            )
        }
    }

    private companion object {
        val animationInterval: Duration = Duration.ofMillis(250)
    }
}

data class CachedRailMapSnapshot(
    val generatedAt: Instant,
    val snapshot: RailMapSnapshot
) {
    fun toSnapshot(
        clock: Clock,
        cached: Boolean,
        railMapMotionEngine: RailMapMotionEngine
    ) =
        toSnapshot(clock, cached, railMapMotionEngine, Instant.now(clock))

    fun toSnapshot(
        clock: Clock,
        cached: Boolean,
        railMapMotionEngine: RailMapMotionEngine,
        currentTime: Instant
    ): RailMapSnapshot {
        val animatedSnapshot = railMapMotionEngine.advance(snapshot, currentTime)
        val cacheAge = if (cached) {
            Duration.between(generatedAt, currentTime).let { duration ->
                if (duration.isNegative) Duration.ZERO else duration
            }
        } else {
            Duration.ZERO
        }

        return RailMapSnapshot(
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
        animatedSnapshot: RailMapSnapshot
    ): RailMapTrainPositions {
        val cacheAge = if (cached) {
            Duration.between(generatedAt, currentTime).let { duration ->
                if (duration.isNegative) Duration.ZERO else duration
            }
        } else {
            Duration.ZERO
        }

        return RailMapTrainPositions(
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
