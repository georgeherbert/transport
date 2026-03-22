package transport

interface TubeLineGeometrySource {
    suspend fun getTubeLineGeometry(): TransportResult<List<TubeLineGeometryRecord>>
}
