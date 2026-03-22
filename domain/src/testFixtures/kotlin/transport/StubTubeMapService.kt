package transport

class StubTubeMapService(
    private val result: suspend (Boolean) -> TransportResult<TubeMapSnapshot>
) : TubeMapService {
    override suspend fun getTubeMap(forceRefresh: Boolean) =
        result(forceRefresh)
}
