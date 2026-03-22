package transport

class StubTubeLineMapService(
    private val result: suspend () -> TransportResult<TubeLineMap>
) : TubeLineMapService {
    override suspend fun getTubeLineMap() =
        result()
}
