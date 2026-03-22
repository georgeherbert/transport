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
    private val tubeData: TubeData,
    private val tubeLineGeometrySource: TubeLineGeometrySource
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
        tubeLineGeometrySource.getTubeLineGeometry()
            .flatMap { lineGeometry ->
                supportedRailLineIds
                    .map { lineId -> tubeData.fetchLineRoutes(lineId) }
                    .failFast()
                    .flatMap { routeRecords ->
                        toTubeLineMap(routeRecords, lineGeometry)
                    }
            }

    private fun toTubeLineMap(
        routeRecords: List<TubeLineRouteRecord>,
        lineGeometry: List<TubeLineGeometryRecord>
    ): TransportResult<TubeLineMap> {
        if (routeRecords.isEmpty()) {
            return Failure(TransportError.MetadataUnavailable("TfL returned no supported rail line routes."))
        }

        val geometryByLineId = lineGeometry.associateBy(TubeLineGeometryRecord::lineId)

        return routeRecords
            .map { routeRecord -> toTubeLine(routeRecord, geometryByLineId) }
            .failFast()
            .map(::TubeLineMap)
    }

    private fun toTubeLine(
        routeRecord: TubeLineRouteRecord,
        geometryByLineId: Map<LineId, TubeLineGeometryRecord>
    ): TransportResult<TubeLine> =
        geometryByLineId[routeRecord.lineId]
            ?.let { geometryRecord ->
                Success(
                    TubeLine(
                        routeRecord.lineId,
                        routeRecord.lineName,
                        geometryRecord.paths.map { pathRecord ->
                            TubeLinePath(pathRecord.coordinates)
                        },
                        routeRecord.sequences.map { sequenceRecord ->
                            TubeLineSequence(sequenceRecord.direction, sequenceRecord.stations)
                        }
                    )
                )
            }
            ?: Failure(
                TransportError.MetadataUnavailable(
                    "No imported rail geometry is available for ${routeRecord.lineId.value}."
                )
            )
}
