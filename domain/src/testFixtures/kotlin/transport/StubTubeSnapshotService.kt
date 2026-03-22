package transport

class StubTubeSnapshotService(
    private val result: suspend (Boolean) -> TransportResult<LiveTubeSnapshot>
) : TubeSnapshotService {
    override suspend fun getLiveSnapshot(forceRefresh: Boolean) =
        result(forceRefresh)
}
