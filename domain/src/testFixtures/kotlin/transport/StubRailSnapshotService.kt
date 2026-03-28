package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success

class StubRailSnapshotService : RailSnapshotService {
    private var defaultResult: TransportResult<LiveRailSnapshot> =
        Failure(TransportError.SnapshotUnavailable("No canned live rail snapshot."))
    private val resultsByRefresh = mutableMapOf<Boolean, TransportResult<LiveRailSnapshot>>()
    private val queuedResults = mutableListOf<TransportResult<LiveRailSnapshot>>()

    val refreshRequests = mutableListOf<Boolean>()

    fun returns(snapshot: LiveRailSnapshot) {
        defaultResult = Success(snapshot)
    }

    fun returns(
        forceRefresh: Boolean,
        snapshot: LiveRailSnapshot
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

    fun thenReturns(snapshot: LiveRailSnapshot) {
        queuedResults += Success(snapshot)
    }

    fun thenFailsWith(error: TransportError) {
        queuedResults += Failure(error)
    }

    override suspend fun getLiveSnapshot(forceRefresh: Boolean) =
        run {
            refreshRequests += forceRefresh
            queuedResults
                .takeIf { cannedResults -> cannedResults.isNotEmpty() }
                ?.let(::consumeQueuedResult)
                ?: resultsByRefresh[forceRefresh]
                ?: defaultResult
        }

    private fun consumeQueuedResult(queue: MutableList<TransportResult<LiveRailSnapshot>>) =
        if (queue.size == 1) {
            queue.first()
        } else {
            queue.removeAt(0)
        }
}
