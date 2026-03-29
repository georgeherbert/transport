package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.flatMap
import dev.forkhandles.result4k.map
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface RailMetadataRepository {
    suspend fun getRailNetwork(): TransportResult<RailNetwork>
}

class RealRailMetadataRepository(
    private val railData: RailData
) : RailMetadataRepository {
    private val cachedNetwork = AtomicReference<RailNetwork?>(null)
    private val loadLock = Mutex()

    override suspend fun getRailNetwork() =
        cachedNetwork.get()?.let { network -> Success(network) } ?: loadNetworkWithCache()

    private suspend fun loadNetworkWithCache(): TransportResult<RailNetwork> =
        loadLock.withLock {
            cachedNetwork.get()?.let { network ->
                Success(network)
            } ?: loadRailNetwork()
                .flatMap { network ->
                    cachedNetwork.set(network)
                    Success(network)
                }
        }

    private suspend fun loadRailNetwork(): TransportResult<RailNetwork> =
        supportedRailModes
            .map { mode -> railData.fetchModeStations(mode) }
            .failFast()
            .map(List<List<RailStationRecord>>::flatten)
            .flatMap(::buildRailNetwork)

    private fun buildRailNetwork(stationRecords: List<RailStationRecord>): TransportResult<RailNetwork> =
        if (stationRecords.isEmpty()) {
            Failure(TransportError.MetadataUnavailable("TfL returned no supported rail stations."))
        } else {
            val stationsById = stationRecords
                .groupBy(RailStationRecord::stationId)
                .mapValues { entry ->
                    val firstRecord = entry.value.first()
                    RailStation(
                        firstRecord.stationId,
                        firstRecord.name,
                        firstRecord.coordinate,
                        entry.value.map(RailStationRecord::lineId).toSet()
                    )
                }

            Success(RailNetwork(stationsById))
        }
}
