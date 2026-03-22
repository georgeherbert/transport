package transport

import dev.forkhandles.result4k.flatMap
import dev.forkhandles.result4k.map

interface TubeMapService {
    suspend fun getTubeMap(forceRefresh: Boolean): TransportResult<TubeMapSnapshot>
}

class RealTubeMapService(
    private val tubeSnapshotService: TubeSnapshotService,
    private val tubeLineMapService: TubeLineMapService,
    private val tubeMapProjector: TubeMapProjector
) : TubeMapService {
    override suspend fun getTubeMap(forceRefresh: Boolean) =
        tubeSnapshotService.getLiveSnapshot(forceRefresh)
            .flatMap { snapshot ->
                tubeLineMapService.getTubeLineMap()
                    .map { lineMap -> tubeMapProjector.project(snapshot, lineMap) }
            }
}
