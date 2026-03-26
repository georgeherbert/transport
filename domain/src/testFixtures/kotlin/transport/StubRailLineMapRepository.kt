package transport

class StubRailLineMapRepository(
    private val result: TransportResult<RailLineMap>
) : RailLineMapRepository {
    override suspend fun getRailLineMap() =
        result
}
