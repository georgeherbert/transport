package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success

class StubRailMetadataRepository : RailMetadataRepository {
    private var result: TransportResult<RailNetwork> =
        Failure(TransportError.MetadataUnavailable("No canned rail network."))

    var requestCount = 0
        private set

    fun returns(railNetwork: RailNetwork) {
        result = Success(railNetwork)
    }

    fun failsWith(error: TransportError) {
        result = Failure(error)
    }

    override suspend fun getRailNetwork() =
        run {
            requestCount += 1
            result
        }
}
