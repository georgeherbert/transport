package transport

interface RailLineMapService {
    suspend fun getRailLineMap(): TransportResult<RailLineMap>
}

class RealRailLineMapService(
    private val railLineMapRepository: RailLineMapRepository
) : RailLineMapService {
    override suspend fun getRailLineMap() =
        railLineMapRepository.getRailLineMap()
}
