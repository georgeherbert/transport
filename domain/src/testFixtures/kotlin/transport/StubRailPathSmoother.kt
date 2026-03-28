package transport

class StubRailPathSmoother : RailPathSmoother {
    private var smoothedLineMap: RailLineMap? = null

    val requests = mutableListOf<RailLineMap>()

    fun returns(lineMap: RailLineMap) {
        smoothedLineMap = lineMap
    }

    override fun smooth(lineMap: RailLineMap) =
        run {
            requests += lineMap
            smoothedLineMap ?: lineMap
        }
}
