package transport

import java.time.Instant

class StubRailMapMotionEngine : RailMapMotionEngine {
    private var observedSnapshotResult: RailMapSnapshot? = null
    private var advancedSnapshotResult: RailMapSnapshot? = null
    private var advanceAfterThreshold: Instant? = null
    private var advanceAfterResult: RailMapSnapshot? = null

    val observedSnapshots = mutableListOf<RailMapSnapshot>()
    val advanceRequests = mutableListOf<AdvanceRequest>()

    fun observeReturns(snapshot: RailMapSnapshot) {
        observedSnapshotResult = snapshot
    }

    fun advanceReturns(snapshot: RailMapSnapshot) {
        advancedSnapshotResult = snapshot
    }

    fun advanceReturnsAfter(
        threshold: Instant,
        snapshot: RailMapSnapshot
    ) {
        advanceAfterThreshold = threshold
        advanceAfterResult = snapshot
    }

    override fun observe(snapshot: RailMapSnapshot) =
        run {
            observedSnapshots += snapshot
            observedSnapshotResult ?: snapshot
        }

    override fun advance(
        snapshot: RailMapSnapshot,
        currentTime: Instant
    ) =
        run {
            advanceRequests += AdvanceRequest(snapshot, currentTime)
            advanceAfterThreshold
                ?.takeIf { threshold -> currentTime > threshold }
                ?.let { _ -> advanceAfterResult }
                ?: advancedSnapshotResult
                ?: snapshot
        }
}

data class AdvanceRequest(
    val snapshot: RailMapSnapshot,
    val currentTime: Instant
)
