package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success

class FakeRailLineGeometrySource : RailLineGeometrySource {
    private var result: TransportResult<List<RailLineGeometryRecord>> =
        Failure(TransportError.MetadataUnavailable("No canned rail line geometry."))

    var requestCount = 0
        private set

    fun returns(records: List<RailLineGeometryRecord>) {
        result = Success(records)
    }

    fun failsWith(error: TransportError) {
        result = Failure(error)
    }

    override suspend fun getRailLineGeometry() =
        run {
            requestCount += 1
            result
        }
}
