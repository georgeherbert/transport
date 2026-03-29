package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success

class StubRailMapQuery : RailMapQuery {
    private var defaultResult: TransportResult<RailMapSnapshot> =
        Failure(TransportError.SnapshotUnavailable("No canned rail map snapshot."))
    private val resultsByRefresh = mutableMapOf<Boolean, TransportResult<RailMapSnapshot>>()
    private val queuedResults = mutableListOf<TransportResult<RailMapSnapshot>>()

    val refreshRequests = mutableListOf<Boolean>()

    fun returns(snapshot: RailMapSnapshot) {
        defaultResult = Success(snapshot)
    }

    fun returns(
        forceRefresh: Boolean,
        snapshot: RailMapSnapshot
    ) {
        resultsByRefresh[forceRefresh] = Success(snapshot)
    }

    fun failsWith(error: TransportError) {
        defaultResult = Failure(error)
    }

    fun failsWith(
        forceRefresh: Boolean,
        error: TransportError
    ) {
        resultsByRefresh[forceRefresh] = Failure(error)
    }

    fun thenReturns(snapshot: RailMapSnapshot) {
        queuedResults += Success(snapshot)
    }

    fun thenFailsWith(error: TransportError) {
        queuedResults += Failure(error)
    }

    override suspend fun getRailMap(forceRefresh: Boolean) =
        run {
            refreshRequests += forceRefresh
            queuedResults
                .takeIf { cannedResults -> cannedResults.isNotEmpty() }
                ?.let(::consumeQueuedResult)
                ?: resultsByRefresh[forceRefresh]
                ?: defaultResult
        }

    private fun consumeQueuedResult(queue: MutableList<TransportResult<RailMapSnapshot>>) =
        if (queue.size == 1) {
            queue.first()
        } else {
            queue.removeAt(0)
        }
}
