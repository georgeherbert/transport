package transport

import kotlinx.coroutines.flow.Flow

class StubRailMapFeedService(
    private val onStart: suspend () -> Unit,
    private val onGetRailMap: suspend (Boolean) -> TransportResult<RailMapSnapshot>,
    private val onCurrentError: () -> TransportError?,
    private val flow: Flow<RailMapFeedUpdate>
) : RailMapFeedService {
    override suspend fun start() =
        onStart()

    override suspend fun getRailMap(forceRefresh: Boolean) =
        onGetRailMap(forceRefresh)

    override fun currentError() =
        onCurrentError()

    override fun updates() =
        flow
}
