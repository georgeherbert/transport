package transport

class StubTubeMetadataRepository(
    private val result: TransportResult<TubeNetwork>
) : TubeMetadataRepository {
    override suspend fun getTubeNetwork() =
        result
}
