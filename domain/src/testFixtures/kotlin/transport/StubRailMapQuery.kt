package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success

class StubRailMapQuery : RailMapQuery {
    private var defaultResult: TransportResult<RailMapSnapshot> =
        Failure(TransportError.SnapshotUnavailable("No canned rail map snapshot."))
    private val queuedResults = mutableListOf<TransportResult<RailMapSnapshot>>()

    var refreshRequests = 0
        private set

    fun returns(snapshot: RailMapSnapshot) {
        defaultResult = Success(snapshot)
    }

    fun failsWith(error: TransportError) {
        defaultResult = Failure(error)
    }

    fun thenReturns(snapshot: RailMapSnapshot) {
        queuedResults += Success(snapshot)
    }

    fun thenFailsWith(error: TransportError) {
        queuedResults += Failure(error)
    }

    override suspend fun refreshRailMap() =
        run {
            refreshRequests += 1
            queuedResults
                .takeIf { cannedResults -> cannedResults.isNotEmpty() }
                ?.let(::consumeQueuedResult)
                ?: defaultResult
        }

    private fun consumeQueuedResult(queue: MutableList<TransportResult<RailMapSnapshot>>) =
        if (queue.size == 1) {
            queue.first()
        } else {
            queue.removeAt(0)
        }
}
