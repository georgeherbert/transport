package transport

class StubRailMapService(
    private val result: suspend (Boolean) -> TransportResult<RailMapSnapshot>
) : RailMapService {
    override suspend fun getRailMap(forceRefresh: Boolean) =
        result(forceRefresh)
}
