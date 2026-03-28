package transport

class StubRailLinePathProjectionFactory : RailLinePathProjectionFactory {
    private var defaultProjection: RailLinePathProjection? = null
    private val projectionsByPath = linkedMapOf<RailLinePath, RailLinePathProjection>()

    val requestedPaths = mutableListOf<RailLinePath>()

    fun returns(projection: RailLinePathProjection) {
        defaultProjection = projection
    }

    fun returns(
        path: RailLinePath,
        projection: RailLinePathProjection
    ) {
        projectionsByPath[path] = projection
    }

    override fun create(path: RailLinePath) =
        run {
            requestedPaths += path
            projectionsByPath[path] ?: defaultProjection ?: StubRailLinePathProjection(path, 0.0)
        }
}
