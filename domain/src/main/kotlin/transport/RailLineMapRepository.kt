package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.flatMap
import dev.forkhandles.result4k.map
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface RailLineMapRepository {
    suspend fun getRailLineMap(): TransportResult<RailLineMap>
}

class RealRailLineMapRepository(
    private val railData: RailData,
    private val railLineGeometrySource: RailLineGeometrySource
) : RailLineMapRepository {
    private val cachedLineMap = AtomicReference<RailLineMap?>(null)
    private val loadLock = Mutex()

    override suspend fun getRailLineMap() =
        cachedLineMap.get()?.let { lineMap -> Success(lineMap) } ?: loadLineMapWithCache()

    private suspend fun loadLineMapWithCache(): TransportResult<RailLineMap> =
        loadLock.withLock {
            cachedLineMap.get()?.let { lineMap ->
                Success(lineMap)
            } ?: loadLineMap()
                .flatMap { lineMap ->
                    cachedLineMap.set(lineMap)
                    Success(lineMap)
                }
        }

    private suspend fun loadLineMap(): TransportResult<RailLineMap> =
        railLineGeometrySource.getRailLineGeometry()
            .flatMap { lineGeometry ->
                supportedRailLineIds
                    .map { lineId -> railData.fetchLineRoutes(lineId) }
                    .failFast()
                    .flatMap { routeRecords ->
                        toRailLineMap(routeRecords, lineGeometry)
                    }
            }

    private fun toRailLineMap(
        routeRecords: List<RailLineRouteRecord>,
        lineGeometry: List<RailLineGeometryRecord>
    ): TransportResult<RailLineMap> {
        if (routeRecords.isEmpty()) {
            return Failure(TransportError.MetadataUnavailable("TfL returned no supported rail line routes."))
        }

        val geometryByLineId = lineGeometry.associateBy(RailLineGeometryRecord::lineId)

        return routeRecords
            .map { routeRecord -> toRailLine(routeRecord, geometryByLineId) }
            .failFast()
            .map(::RailLineMap)
    }

    private fun toRailLine(
        routeRecord: RailLineRouteRecord,
        geometryByLineId: Map<LineId, RailLineGeometryRecord>
    ): TransportResult<RailLine> =
        geometryByLineId[routeRecord.lineId]
            ?.let { geometryRecord ->
                Success(
                    RailLine(
                        routeRecord.lineId,
                        routeRecord.lineName,
                        geometryRecord.paths.map { pathRecord ->
                            RailLinePath(pathRecord.coordinates)
                        },
                        routeRecord.sequences.map { sequenceRecord ->
                            RailLineSequence(sequenceRecord.direction, sequenceRecord.stations)
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
