package transport

class StubRailSnapshotService(
    private val result: suspend (Boolean) -> TransportResult<LiveRailSnapshot>
) : RailSnapshotService {
    override suspend fun getLiveSnapshot(forceRefresh: Boolean) =
        result(forceRefresh)
}
