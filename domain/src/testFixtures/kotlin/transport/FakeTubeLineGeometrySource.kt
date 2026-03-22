package transport

class FakeTubeLineGeometrySource(
    private val geometryHandler: suspend () -> TransportResult<List<TubeLineGeometryRecord>>
) : TubeLineGeometrySource {
    override suspend fun getTubeLineGeometry() =
        geometryHandler()
}
