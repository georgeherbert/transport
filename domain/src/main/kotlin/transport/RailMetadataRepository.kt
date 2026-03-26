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

            Success(RailNetwork(stationsById, buildAliasIndex(stationsById.values)))
        }
}

fun buildAliasIndex(stations: Collection<RailStation>): Map<String, List<RailStation>> =
    linkedMapOf<String, MutableList<RailStation>>().apply {
        for (station in stations) {
            for (alias in stationNameVariants(station.name)) {
                getOrPut(alias) { mutableListOf() } += station
            }
        }
    }.mapValues { entry -> entry.value.toList() }

fun stationNameVariants(name: StationName): Set<String> =
    setOf(
        name.value,
        name.value.removeSuffix(" Underground Station"),
        name.value.removeSuffix(" Rail Station"),
        name.value.removeSuffix(" Station"),
        name.value.replace(Regex("\\s*\\([^)]*\\)"), "")
    ).map(::normalizeStationName).toSet()

fun normalizeStationName(name: String): String =
    name
        .replace(Regex("\\s*\\([^)]*\\)"), " ")
        .replace(Regex("\\bPlatform\\b.*$", RegexOption.IGNORE_CASE), " ")
        .replace("&", " and ")
        .replace("'", "")
        .replace(".", "")
        .replace(",", " ")
        .replace("/", " ")
        .replace("-", " ")
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
        .removeSuffix(" underground station")
        .removeSuffix(" rail station")
        .removeSuffix(" station")
        .trim()
