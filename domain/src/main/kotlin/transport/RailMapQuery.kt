package transport

import dev.forkhandles.result4k.flatMap
import dev.forkhandles.result4k.map

interface RailMapQuery {
    suspend fun getRailMap(forceRefresh: Boolean): TransportResult<RailMapSnapshot>
}

class RealRailMapQuery(
    private val railSnapshotService: RailSnapshotService,
    private val railLineMapService: RailLineMapService,
    private val railMapProjector: RailMapProjector
) : RailMapQuery {
    override suspend fun getRailMap(forceRefresh: Boolean) =
        railSnapshotService.getLiveSnapshot(forceRefresh)
            .flatMap { snapshot ->
                railLineMapService.getRailLineMap()
                    .map { lineMap -> railMapProjector.project(snapshot, lineMap) }
            }
}
