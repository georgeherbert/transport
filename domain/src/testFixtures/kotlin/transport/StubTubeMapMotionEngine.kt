package transport

import java.time.Instant

class StubTubeMapMotionEngine(
    private val onObserve: (TubeMapSnapshot) -> TubeMapSnapshot,
    private val onAdvance: (TubeMapSnapshot, Instant) -> TubeMapSnapshot
) : TubeMapMotionEngine {
    override fun observe(snapshot: TubeMapSnapshot) =
        onObserve(snapshot)

    override fun advance(
        snapshot: TubeMapSnapshot,
        currentTime: Instant
    ) =
        onAdvance(snapshot, currentTime)
}
