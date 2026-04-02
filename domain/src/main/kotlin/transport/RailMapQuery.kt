package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.map

interface RailMapQuery {
    suspend fun refreshRailMap(): TransportResult<RailMapSnapshot>
}

class RealRailMapQuery(
    private val railSnapshotService: RailSnapshotService,
    private val railLineMapService: RailLineMapService,
    private val railMapProjector: RailMapProjector
) : RailMapQuery {
    override suspend fun refreshRailMap() =
        when (val snapshotResult = railSnapshotService.refreshLiveSnapshot()) {
            is Success ->
                railLineMapService.getRailLineMap()
                    .map { lineMap -> railMapProjector.project(snapshotResult.value, lineMap) }
            is Failure -> Failure(snapshotResult.reason)
        }
}
