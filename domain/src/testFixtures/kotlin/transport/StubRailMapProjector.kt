package transport

class StubRailMapProjector : RailMapProjector {
    private var defaultSnapshot: RailMapSnapshot? = null
    private val queuedSnapshots = mutableListOf<RailMapSnapshot>()

    val requests = mutableListOf<ProjectRequest>()

    fun returns(snapshot: RailMapSnapshot) {
        defaultSnapshot = snapshot
    }

    fun thenReturns(snapshot: RailMapSnapshot) {
        queuedSnapshots += snapshot
    }

    override fun project(snapshot: LiveRailSnapshot, lineMap: RailLineMap) =
        run {
            val request = ProjectRequest(snapshot, lineMap)
            requests += request
            queuedSnapshots
                .takeIf { cannedSnapshots -> cannedSnapshots.isNotEmpty() }
                ?.let(::consumeQueuedSnapshot)
                ?: defaultSnapshot
                ?: defaultProjection(request)
        }

    private fun consumeQueuedSnapshot(queue: MutableList<RailMapSnapshot>) =
        if (queue.size == 1) {
            queue.first()
        } else {
            queue.removeAt(0)
        }

    private fun defaultProjection(request: ProjectRequest) =
        RailMapSnapshot(
            request.snapshot.source,
            request.snapshot.generatedAt,
            request.snapshot.cached,
            request.snapshot.cacheAge,
            request.snapshot.stationsQueried,
            request.snapshot.stationsFailed,
            request.snapshot.partial,
            request.snapshot.trainCount,
            request.lineMap.lines,
            emptyList(),
            request.snapshot.trains.mapNotNull { train ->
                train.lineIds.firstOrNull()?.let { lineId ->
                    RailMapTrain(
                        train.trainId,
                        train.vehicleId,
                        lineId,
                        train.lineNames.firstOrNull() ?: LineName(lineId.value),
                        train.direction,
                        train.destinationName,
                        train.towards,
                        train.currentLocation,
                        train.nextStop,
                        null,
                        null,
                        train.expectedArrival,
                        train.observedAt,
                        train.futureArrivals
                    )
                }
            }
        )
}

data class ProjectRequest(
    val snapshot: LiveRailSnapshot,
    val lineMap: RailLineMap
)
