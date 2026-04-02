package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success

class StubRailSnapshotService : RailSnapshotService {
    private var defaultResult: TransportResult<LiveRailSnapshot> =
        Failure(TransportError.SnapshotUnavailable("No canned live rail snapshot."))
    private val queuedResults = mutableListOf<TransportResult<LiveRailSnapshot>>()

    var refreshRequests = 0
        private set

    fun returns(snapshot: LiveRailSnapshot) {
        defaultResult = Success(snapshot)
    }

    fun failsWith(error: TransportError) {
        defaultResult = Failure(error)
    }

    fun thenReturns(snapshot: LiveRailSnapshot) {
        queuedResults += Success(snapshot)
    }

    fun thenFailsWith(error: TransportError) {
        queuedResults += Failure(error)
    }

    override suspend fun refreshLiveSnapshot() =
        run {
            refreshRequests += 1
            queuedResults
                .takeIf { cannedResults -> cannedResults.isNotEmpty() }
                ?.let(::consumeQueuedResult)
                ?: defaultResult
        }

    private fun consumeQueuedResult(queue: MutableList<TransportResult<LiveRailSnapshot>>) =
        if (queue.size == 1) {
            queue.first()
        } else {
            queue.removeAt(0)
        }
}
