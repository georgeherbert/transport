package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEqualTo

class RealTubeLineMapRepositoryTest {
    @Test
    fun `getTubeLineMap loads route geometry for the tube lines`() {
        runBlocking {
            val tubeData = FakeTubeData(
                modeStationHandler = { mode ->
                    Failure(TransportError.MetadataUnavailable(mode.value))
                },
                lineRouteHandler = { lineId ->
                    Success(
                        TubeLineRouteRecord(
                            lineId,
                            LineName(lineId.value.replaceFirstChar(Char::titlecase)),
                            listOf(
                                TubeLinePathRecord(
                                    listOf(
                                        GeoCoordinate(51.0, -0.1),
                                        GeoCoordinate(51.1, -0.2)
                                    )
                                )
                            ),
                            emptyList()
                        )
                    )
                },
                predictionHandler = { mode ->
                    Failure(TransportError.SnapshotUnavailable("unused"))
                }
            )
            val repository = RealTubeLineMapRepository(tubeData)

            val result = repository.getTubeLineMap()

            expectThat(result).isSuccess().get { lines }.hasSize(supportedRailLineIds.size)
            expectThat(result).isSuccess().get { lines.first().paths.first().coordinates.first().lat }.isEqualTo(51.0)
        }
    }

    @Test
    fun `getTubeLineMap reuses the cached line map`() {
        runBlocking {
            val requests = AtomicInteger(0)
            val tubeData = FakeTubeData(
                modeStationHandler = { mode ->
                    Failure(TransportError.MetadataUnavailable(mode.value))
                },
                lineRouteHandler = { lineId ->
                    requests.incrementAndGet()
                    Success(
                        TubeLineRouteRecord(
                            lineId,
                            LineName(lineId.value.replaceFirstChar(Char::titlecase)),
                            emptyList(),
                            emptyList()
                        )
                    )
                },
                predictionHandler = { mode ->
                    Failure(TransportError.SnapshotUnavailable("unused"))
                }
            )
            val repository = RealTubeLineMapRepository(tubeData)

            expectThat(repository.getTubeLineMap()).isSuccess()
            expectThat(repository.getTubeLineMap()).isSuccess()

            expectThat(requests.get()).isEqualTo(supportedRailLineIds.size)
        }
    }

    @Test
    fun `getTubeLineMap returns failure when a route call fails`() {
        runBlocking {
            val tubeData = FakeTubeData(
                modeStationHandler = { mode ->
                    Failure(TransportError.MetadataUnavailable(mode.value))
                },
                lineRouteHandler = { lineId ->
                    if (lineId == supportedRailLineIds.first()) {
                        Failure(TransportError.UpstreamNetworkFailure("/Line/${lineId.value}/Route/Sequence/all", "boom"))
                    } else {
                        Success(
                            TubeLineRouteRecord(
                                lineId,
                                LineName(lineId.value.replaceFirstChar(Char::titlecase)),
                                emptyList(),
                                emptyList()
                            )
                        )
                    }
                },
                predictionHandler = { mode ->
                    Failure(TransportError.SnapshotUnavailable("unused"))
                }
            )
            val repository = RealTubeLineMapRepository(tubeData)

            val result = repository.getTubeLineMap()

            expectThat(result).isFailure().isA<TransportError.UpstreamNetworkFailure>()
        }
    }
}
