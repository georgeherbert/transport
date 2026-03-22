package transport

interface TubeLineMapService {
    suspend fun getTubeLineMap(): TransportResult<TubeLineMap>
}

class RealTubeLineMapService(
    private val tubeLineMapRepository: TubeLineMapRepository
) : TubeLineMapService {
    override suspend fun getTubeLineMap() =
        tubeLineMapRepository.getTubeLineMap()
}
