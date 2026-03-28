package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success

class StubRailLineMapService : RailLineMapService {
    private var result: TransportResult<RailLineMap> =
        Failure(TransportError.MetadataUnavailable("No canned rail line map."))

    var requestCount = 0
        private set

    fun returns(lineMap: RailLineMap) {
        result = Success(lineMap)
    }

    fun failsWith(error: TransportError) {
        result = Failure(error)
    }

    override suspend fun getRailLineMap() =
        run {
            requestCount += 1
            result
        }
}
