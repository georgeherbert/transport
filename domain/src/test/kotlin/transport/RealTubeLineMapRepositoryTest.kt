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
    fun `getTubeLineMap combines TfL line sequences with imported line geometry`() {
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
                            emptyList(),
                            emptyList()
                        )
                    )
                },
                predictionHandler = { mode ->
                    Failure(TransportError.SnapshotUnavailable("unused"))
                },
                vehiclePredictionHandler = { vehicleIds ->
                    Failure(TransportError.SnapshotUnavailable("unused"))
                }
            )
            val lineGeometrySource = FakeTubeLineGeometrySource {
                Success(
                    supportedRailLineIds.map { lineId ->
                        TubeLineGeometryRecord(
                            lineId,
                            listOf(
                                TubeLinePathRecord(
                                    listOf(
                                        GeoCoordinate(51.0, -0.1),
                                        GeoCoordinate(51.1, -0.2)
                                    )
                                )
                            )
                        )
                    }
                )
            }
            val repository = RealTubeLineMapRepository(tubeData, lineGeometrySource)

            val result = repository.getTubeLineMap()

            expectThat(result).isSuccess().get { lines }.hasSize(supportedRailLineIds.size)
            expectThat(result).isSuccess().get { lines.first().paths.first().coordinates.first().lat }.isEqualTo(51.0)
        }
    }

    @Test
    fun `getTubeLineMap reuses the cached line map`() {
        runBlocking {
            val routeRequests = AtomicInteger(0)
            val geometryRequests = AtomicInteger(0)
            val tubeData = FakeTubeData(
                modeStationHandler = { mode ->
                    Failure(TransportError.MetadataUnavailable(mode.value))
                },
                lineRouteHandler = { lineId ->
                    routeRequests.incrementAndGet()
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
                },
                vehiclePredictionHandler = { vehicleIds ->
                    Failure(TransportError.SnapshotUnavailable("unused"))
                }
            )
            val lineGeometrySource = FakeTubeLineGeometrySource {
                geometryRequests.incrementAndGet()
                Success(
                    supportedRailLineIds.map { lineId ->
                        TubeLineGeometryRecord(
                            lineId,
                            listOf(
                                TubeLinePathRecord(
                                    listOf(
                                        GeoCoordinate(51.0, -0.1),
                                        GeoCoordinate(51.1, -0.2)
                                    )
                                )
                            )
                        )
                    }
                )
            }
            val repository = RealTubeLineMapRepository(tubeData, lineGeometrySource)

            expectThat(repository.getTubeLineMap()).isSuccess()
            expectThat(repository.getTubeLineMap()).isSuccess()

            expectThat(routeRequests.get()).isEqualTo(supportedRailLineIds.size)
            expectThat(geometryRequests.get()).isEqualTo(1)
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
                },
                vehiclePredictionHandler = { vehicleIds ->
                    Failure(TransportError.SnapshotUnavailable("unused"))
                }
            )
            val lineGeometrySource = FakeTubeLineGeometrySource {
                Success(
                    supportedRailLineIds.map { lineId ->
                        TubeLineGeometryRecord(
                            lineId,
                            listOf(
                                TubeLinePathRecord(
                                    listOf(
                                        GeoCoordinate(51.0, -0.1),
                                        GeoCoordinate(51.1, -0.2)
                                    )
                                )
                            )
                        )
                    }
                )
            }
            val repository = RealTubeLineMapRepository(tubeData, lineGeometrySource)

            val result = repository.getTubeLineMap()

            expectThat(result).isFailure().isA<TransportError.UpstreamNetworkFailure>()
        }
    }

    @Test
    fun `getTubeLineMap returns failure when imported geometry is missing for a supported line`() {
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
                            emptyList(),
                            emptyList()
                        )
                    )
                },
                predictionHandler = { mode ->
                    Failure(TransportError.SnapshotUnavailable("unused"))
                },
                vehiclePredictionHandler = { vehicleIds ->
                    Failure(TransportError.SnapshotUnavailable("unused"))
                }
            )
            val lineGeometrySource = FakeTubeLineGeometrySource {
                Success(
                    supportedRailLineIds.drop(1).map { lineId ->
                        TubeLineGeometryRecord(
                            lineId,
                            listOf(
                                TubeLinePathRecord(
                                    listOf(
                                        GeoCoordinate(51.0, -0.1),
                                        GeoCoordinate(51.1, -0.2)
                                    )
                                )
                            )
                        )
                    }
                )
            }
            val repository = RealTubeLineMapRepository(tubeData, lineGeometrySource)

            val result = repository.getTubeLineMap()

            expectThat(result).isFailure().isA<TransportError.MetadataUnavailable>()
        }
    }

    @Test
    fun `getTubeLineMap returns geometry source failures unchanged`() {
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
                            emptyList(),
                            emptyList()
                        )
                    )
                },
                predictionHandler = { mode ->
                    Failure(TransportError.SnapshotUnavailable("unused"))
                },
                vehiclePredictionHandler = { vehicleIds ->
                    Failure(TransportError.SnapshotUnavailable("unused"))
                }
            )
            val lineGeometrySource = FakeTubeLineGeometrySource {
                Failure(TransportError.MetadataUnavailable("missing geometry"))
            }
            val repository = RealTubeLineMapRepository(tubeData, lineGeometrySource)

            val result = repository.getTubeLineMap()

            expectThat(result).isFailure().isA<TransportError.MetadataUnavailable>()
        }
    }
}
