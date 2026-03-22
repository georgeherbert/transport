package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.flatMap
import dev.forkhandles.result4k.map
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface TubeMetadataRepository {
    suspend fun getTubeNetwork(): TransportResult<TubeNetwork>
}

class RealTubeMetadataRepository(
    private val tubeData: TubeData
) : TubeMetadataRepository {
    private val cachedNetwork = AtomicReference<TubeNetwork?>(null)
    private val loadLock = Mutex()

    override suspend fun getTubeNetwork() =
        cachedNetwork.get()?.let { network -> Success(network) } ?: loadNetworkWithCache()

    private suspend fun loadNetworkWithCache(): TransportResult<TubeNetwork> =
        loadLock.withLock {
            cachedNetwork.get()?.let { network ->
                Success(network)
            } ?: loadTubeNetwork()
                .flatMap { network ->
                    cachedNetwork.set(network)
                    Success(network)
                }
        }

    private suspend fun loadTubeNetwork(): TransportResult<TubeNetwork> =
        supportedRailModes
            .map { mode -> tubeData.fetchModeStations(mode) }
            .failFast()
            .map(List<List<TubeStationRecord>>::flatten)
            .flatMap(::buildTubeNetwork)

    private fun buildTubeNetwork(stationRecords: List<TubeStationRecord>): TransportResult<TubeNetwork> {
        if (stationRecords.isEmpty()) {
            return Failure(TransportError.MetadataUnavailable("TfL returned no supported rail stations."))
        }

        val stationsById = stationRecords
            .groupBy(TubeStationRecord::stationId)
            .mapValues { entry ->
                val firstRecord = entry.value.first()
                TubeStation(
                    firstRecord.stationId,
                    firstRecord.name,
                    firstRecord.coordinate,
                    entry.value.map(TubeStationRecord::lineId).toSet()
                )
            }

        return Success(TubeNetwork(stationsById, buildAliasIndex(stationsById.values)))
    }
}

fun buildAliasIndex(stations: Collection<TubeStation>): Map<String, List<TubeStation>> {
    val aliases = linkedMapOf<String, MutableList<TubeStation>>()
    for (station in stations) {
        for (alias in stationNameVariants(station.name)) {
            aliases.getOrPut(alias) { mutableListOf() } += station
        }
    }
    return aliases.mapValues { entry -> entry.value.toList() }
}

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
