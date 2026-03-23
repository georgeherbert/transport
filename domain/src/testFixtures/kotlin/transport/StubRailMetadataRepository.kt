package transport

class StubRailMetadataRepository(
    private val result: TransportResult<RailNetwork>
) : RailMetadataRepository {
    override suspend fun getRailNetwork() =
        result
}
