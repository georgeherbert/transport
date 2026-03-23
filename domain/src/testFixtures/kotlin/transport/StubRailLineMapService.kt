package transport

class StubRailLineMapService(
    private val result: suspend () -> TransportResult<RailLineMap>
) : RailLineMapService {
    override suspend fun getRailLineMap() =
        result()
}
