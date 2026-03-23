package transport

class FakeRailLineGeometrySource(
    private val geometryHandler: suspend () -> TransportResult<List<RailLineGeometryRecord>>
) : RailLineGeometrySource {
    override suspend fun getRailLineGeometry() =
        geometryHandler()
}
