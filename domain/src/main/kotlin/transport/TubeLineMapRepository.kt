package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.flatMap
import dev.forkhandles.result4k.map
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface TubeLineMapRepository {
    suspend fun getTubeLineMap(): TransportResult<TubeLineMap>
}

class RealTubeLineMapRepository(
    private val tubeData: TubeData
) : TubeLineMapRepository {
    private val cachedLineMap = AtomicReference<TubeLineMap?>(null)
    private val loadLock = Mutex()

    override suspend fun getTubeLineMap() =
        cachedLineMap.get()?.let { lineMap -> Success(lineMap) } ?: loadLineMapWithCache()

    private suspend fun loadLineMapWithCache(): TransportResult<TubeLineMap> =
        loadLock.withLock {
            cachedLineMap.get()?.let { lineMap ->
                Success(lineMap)
            } ?: loadLineMap()
                .flatMap { lineMap ->
                    cachedLineMap.set(lineMap)
                    Success(lineMap)
                }
        }

    private suspend fun loadLineMap(): TransportResult<TubeLineMap> =
        tubeLineIds
            .map { lineId -> tubeData.fetchLineRoutes(lineId) }
            .failFast()
            .flatMap(::toTubeLineMap)

    private fun toTubeLineMap(routeRecords: List<TubeLineRouteRecord>): TransportResult<TubeLineMap> {
        if (routeRecords.isEmpty()) {
            return Failure(TransportError.MetadataUnavailable("TfL returned no Tube line routes."))
        }

        return Success(
            TubeLineMap(
                routeRecords.map { routeRecord ->
                    TubeLine(
                        routeRecord.lineId,
                        routeRecord.lineName,
                        routeRecord.paths.map { pathRecord ->
                            TubeLinePath(pathRecord.coordinates)
                        },
                        routeRecord.sequences.map { sequenceRecord ->
                            TubeLineSequence(sequenceRecord.direction, sequenceRecord.stations)
                        }
                    )
                }
            )
        )
    }
}
