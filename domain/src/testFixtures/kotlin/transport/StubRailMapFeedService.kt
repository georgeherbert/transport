package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class StubRailMapFeedService : RailMapFeedService {
    private var defaultResult: TransportResult<RailMapSnapshot> =
        Failure(TransportError.SnapshotUnavailable("No canned rail map snapshot."))
    private val resultsByRefresh = mutableMapOf<Boolean, TransportResult<RailMapSnapshot>>()
    private var currentErrorValue: TransportError? = null
    private var updateFlow: Flow<RailMapFeedUpdate> = emptyFlow()

    var startCount = 0
        private set

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

    fun reportsCurrentError(error: TransportError?) {
        currentErrorValue = error
    }

    fun emitsUpdates(flow: Flow<RailMapFeedUpdate>) {
        updateFlow = flow
    }

    override suspend fun start() {
        startCount += 1
    }

    override suspend fun getRailMap(forceRefresh: Boolean) =
        run {
            refreshRequests += forceRefresh
            resultsByRefresh[forceRefresh] ?: defaultResult
        }

    override fun currentError() =
        currentErrorValue

    override fun updates() =
        updateFlow
}
