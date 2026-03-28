package transport

class StubRailLineProjectionFactory : RailLineProjectionFactory {
    private var defaultProjection: RailLineProjection? = null
    private val projectionsByLineId = mutableMapOf<LineId, RailLineProjection>()

    val requestedLines = mutableListOf<RailLine>()

    fun returns(projection: RailLineProjection) {
        defaultProjection = projection
    }

    fun returns(
        lineId: LineId,
        projection: RailLineProjection
    ) {
        projectionsByLineId[lineId] = projection
    }

    override fun create(line: RailLine) =
        run {
            requestedLines += line
            projectionsByLineId[line.id] ?: defaultProjection ?: StubRailLineProjection(line)
        }
}
