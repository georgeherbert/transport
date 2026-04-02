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
            request.snapshot.generatedAt,
            request.snapshot.serviceCount,
            request.lineMap.lines,
            emptyList(),
            request.snapshot.services.mapNotNull { service ->
                service.lineIds.firstOrNull()?.let { lineId ->
                    RailMapService(
                        service.serviceId,
                        lineId,
                        service.lineNames.firstOrNull() ?: LineName(lineId.value),
                        service.direction,
                        service.destinationName,
                        service.towards,
                        service.currentLocation,
                        service.nextStop,
                        null,
                        null,
                        service.expectedArrival,
                        service.futureArrivals
                    )
                }
            }
        )
}

data class ProjectRequest(
    val snapshot: LiveRailSnapshot,
    val lineMap: RailLineMap
)
