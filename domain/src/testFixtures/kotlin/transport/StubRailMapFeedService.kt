package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class StubRailMapFeedService : RailMapFeedService {
    private var defaultResult: TransportResult<RailMapSnapshot> =
        Failure(TransportError.SnapshotUnavailable("No canned rail map snapshot."))
    private var currentErrorValue: TransportError? = null
    private var updateFlow: Flow<RailMapFeedUpdate> = emptyFlow()

    fun returns(snapshot: RailMapSnapshot) {
        defaultResult = Success(snapshot)
    }

    fun failsWith(error: TransportError) {
        defaultResult = Failure(error)
    }

    fun reportsCurrentError(error: TransportError?) {
        currentErrorValue = error
    }

    fun emitsUpdates(flow: Flow<RailMapFeedUpdate>) {
        updateFlow = flow
    }

    override suspend fun start() = Unit

    override suspend fun getRailMap() =
        defaultResult

    override fun currentError() =
        currentErrorValue

    override fun updates() =
        updateFlow
}
