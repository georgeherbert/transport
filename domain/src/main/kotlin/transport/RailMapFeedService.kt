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
    data class ServicePositionsUpdated(val servicePositions: RailMapServicePositions) : RailMapFeedUpdate
    data class ErrorUpdated(val error: TransportError) : RailMapFeedUpdate
}

class RealRailMapFeedService(
    private val railMapProvider: RailMapQuery,
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
        if (started.compareAndSet(false, true)) {
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
    }

    override suspend fun getRailMap(forceRefresh: Boolean) =
        run {
            if (forceRefresh) {
                refreshIfDue()
            }

            cachedSnapshot.get()?.let { cached ->
                Success(cached.toSnapshot(railMapMotionEngine, clock.instant()))
            } ?: latestError.get()?.let { error ->
                Failure(error)
            } ?: Failure(TransportError.SnapshotUnavailable("No cached rail map is available yet."))
        }

    override fun currentError() =
        latestError.get()

    override fun updates() =
        updateFlow.asSharedFlow()

    private suspend fun refreshIfDue() {
        refreshLock.withLock {
            val now = clock.instant()
            val lastAttempt = lastRefreshAttemptAt.get()
            if (lastAttempt == null || Duration.between(lastAttempt, now) >= pollInterval) {
                lastRefreshAttemptAt.set(now)

                when (val mapResult = railMapProvider.getRailMap(true)) {
                    is Success -> {
                        val observedSnapshot = railMapMotionEngine.observe(mapResult.value)
                        val cached = CachedRailMapSnapshot(observedSnapshot)
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
    }

    private fun emitAnimatedSnapshotIfAvailable() {
        if (latestError.get() == null) {
            cachedSnapshot.get()?.let { cached ->
                val currentTime = clock.instant()
                val animatedSnapshot = railMapMotionEngine.advance(cached.snapshot, currentTime)
                if (animatedSnapshot.services != cached.snapshot.services) {
                    updateFlow.tryEmit(
                        RailMapFeedUpdate.ServicePositionsUpdated(
                            cached.toServicePositions(animatedSnapshot)
                        )
                    )
                }
            }
        }
    }

    private companion object {
        val animationInterval: Duration = Duration.ofMillis(250)
    }
}

data class CachedRailMapSnapshot(
    val snapshot: RailMapSnapshot
) {
    fun toSnapshot(
        railMapMotionEngine: RailMapMotionEngine,
        currentTime: Instant
    ): RailMapSnapshot =
        railMapMotionEngine.advance(snapshot, currentTime)

    fun toServicePositions(animatedSnapshot: RailMapSnapshot) =
        RailMapServicePositions(
            animatedSnapshot.generatedAt,
            animatedSnapshot.stationsFailed,
            animatedSnapshot.partial,
            animatedSnapshot.serviceCount,
            animatedSnapshot.stations,
            animatedSnapshot.services
        )
}
