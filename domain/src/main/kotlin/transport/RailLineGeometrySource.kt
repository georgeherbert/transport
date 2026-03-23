package transport

interface RailLineGeometrySource {
    suspend fun getRailLineGeometry(): TransportResult<List<RailLineGeometryRecord>>
}
