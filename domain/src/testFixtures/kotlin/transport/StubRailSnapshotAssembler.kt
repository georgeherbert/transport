package transport

import java.time.Duration
import java.time.Instant

class StubRailSnapshotAssembler : RailSnapshotAssembler {
    private var defaultSnapshot: LiveRailSnapshot? = null
    private val queuedSnapshots = mutableListOf<LiveRailSnapshot>()

    val requests = mutableListOf<AssembleRequest>()

    fun returns(snapshot: LiveRailSnapshot) {
        defaultSnapshot = snapshot
    }

    fun thenReturns(snapshot: LiveRailSnapshot) {
        queuedSnapshots += snapshot
    }

    override fun assemble(
        railNetwork: RailNetwork,
        predictions: List<RailPredictionRecord>,
        generatedAt: Instant,
        stationsQueried: StationQueryCount,
        stationsFailed: StationFailureCount
    ) =
        run {
            val request = AssembleRequest(railNetwork, predictions, generatedAt, stationsQueried, stationsFailed)
            requests += request
            queuedSnapshots
                .takeIf { cannedSnapshots -> cannedSnapshots.isNotEmpty() }
                ?.let(::consumeQueuedSnapshot)
                ?: defaultSnapshot
                ?: defaultSnapshot(request)
        }

    private fun consumeQueuedSnapshot(queue: MutableList<LiveRailSnapshot>) =
        if (queue.size == 1) {
            queue.first()
        } else {
            queue.removeAt(0)
        }

    private fun defaultSnapshot(request: AssembleRequest) =
        LiveRailSnapshot(
            transportSourceName,
            request.generatedAt,
            false,
            Duration.ZERO,
            request.stationsQueried,
            request.stationsFailed,
            request.stationsFailed.value > 0,
            LiveServiceCount(request.predictions.size),
            supportedRailLineIds,
            emptyList()
        )
}

data class AssembleRequest(
    val railNetwork: RailNetwork,
    val predictions: List<RailPredictionRecord>,
    val generatedAt: Instant,
    val stationsQueried: StationQueryCount,
    val stationsFailed: StationFailureCount
)
