package transport

import kotlinx.coroutines.flow.Flow

class StubTubeMapFeedService(
    private val onStart: suspend () -> Unit,
    private val onGetTubeMap: suspend (Boolean) -> TransportResult<TubeMapSnapshot>,
    private val onCurrentError: () -> TransportError?,
    private val flow: Flow<TubeMapFeedUpdate>
) : TubeMapFeedService {
    override suspend fun start() =
        onStart()

    override suspend fun getTubeMap(forceRefresh: Boolean) =
        onGetTubeMap(forceRefresh)

    override fun currentError(): TransportError? =
        onCurrentError()

    override fun updates(): Flow<TubeMapFeedUpdate> =
        flow
}
