package transport

import java.time.Instant

class StubRailMapMotionEngine(
    private val onObserve: (RailMapSnapshot) -> RailMapSnapshot,
    private val onAdvance: (RailMapSnapshot, Instant) -> RailMapSnapshot
) : RailMapMotionEngine {
    override fun observe(snapshot: RailMapSnapshot) =
        onObserve(snapshot)

    override fun advance(
        snapshot: RailMapSnapshot,
        currentTime: Instant
    ) =
        onAdvance(snapshot, currentTime)
}
