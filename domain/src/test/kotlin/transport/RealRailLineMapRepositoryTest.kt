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

class RealRailLineMapRepositoryTest {
    @Test
    fun `getRailLineMap combines TfL line sequences with imported line geometry`() {
        runBlocking {
            val railData = FakeRailData(
                modeStationHandler = { mode ->
                    Failure(TransportError.MetadataUnavailable(mode.value))
                },
                lineRouteHandler = { lineId ->
                    Success(
                        RailLineRouteRecord(
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
            val lineGeometrySource = FakeRailLineGeometrySource {
                Success(
                    supportedRailLineIds.map { lineId ->
                        RailLineGeometryRecord(
                            lineId,
                            listOf(
                                RailLinePathRecord(
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
            val repository = RealRailLineMapRepository(railData, lineGeometrySource)

            val result = repository.getRailLineMap()

            expectThat(result).isSuccess().get { lines }.hasSize(supportedRailLineIds.size)
            expectThat(result).isSuccess().get { lines.first().paths.first().coordinates.first().lat }.isEqualTo(51.0)
        }
    }

    @Test
    fun `getRailLineMap reuses the cached line map`() {
        runBlocking {
            val routeRequests = AtomicInteger(0)
            val geometryRequests = AtomicInteger(0)
            val railData = FakeRailData(
                modeStationHandler = { mode ->
                    Failure(TransportError.MetadataUnavailable(mode.value))
                },
                lineRouteHandler = { lineId ->
                    routeRequests.incrementAndGet()
                    Success(
                        RailLineRouteRecord(
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
            val lineGeometrySource = FakeRailLineGeometrySource {
                geometryRequests.incrementAndGet()
                Success(
                    supportedRailLineIds.map { lineId ->
                        RailLineGeometryRecord(
                            lineId,
                            listOf(
                                RailLinePathRecord(
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
            val repository = RealRailLineMapRepository(railData, lineGeometrySource)

            expectThat(repository.getRailLineMap()).isSuccess()
            expectThat(repository.getRailLineMap()).isSuccess()

            expectThat(routeRequests.get()).isEqualTo(supportedRailLineIds.size)
            expectThat(geometryRequests.get()).isEqualTo(1)
        }
    }

    @Test
    fun `getRailLineMap returns failure when a route call fails`() {
        runBlocking {
            val railData = FakeRailData(
                modeStationHandler = { mode ->
                    Failure(TransportError.MetadataUnavailable(mode.value))
                },
                lineRouteHandler = { lineId ->
                    if (lineId == supportedRailLineIds.first()) {
                        Failure(TransportError.UpstreamNetworkFailure("/Line/${lineId.value}/Route/Sequence/all", "boom"))
                    } else {
                        Success(
                            RailLineRouteRecord(
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
            val lineGeometrySource = FakeRailLineGeometrySource {
                Success(
                    supportedRailLineIds.map { lineId ->
                        RailLineGeometryRecord(
                            lineId,
                            listOf(
                                RailLinePathRecord(
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
            val repository = RealRailLineMapRepository(railData, lineGeometrySource)

            val result = repository.getRailLineMap()

            expectThat(result).isFailure().isA<TransportError.UpstreamNetworkFailure>()
        }
    }

    @Test
    fun `getRailLineMap returns failure when imported geometry is missing for a supported line`() {
        runBlocking {
            val railData = FakeRailData(
                modeStationHandler = { mode ->
                    Failure(TransportError.MetadataUnavailable(mode.value))
                },
                lineRouteHandler = { lineId ->
                    Success(
                        RailLineRouteRecord(
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
            val lineGeometrySource = FakeRailLineGeometrySource {
                Success(
                    supportedRailLineIds.drop(1).map { lineId ->
                        RailLineGeometryRecord(
                            lineId,
                            listOf(
                                RailLinePathRecord(
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
            val repository = RealRailLineMapRepository(railData, lineGeometrySource)

            val result = repository.getRailLineMap()

            expectThat(result).isFailure().isA<TransportError.MetadataUnavailable>()
        }
    }

    @Test
    fun `getRailLineMap returns geometry source failures unchanged`() {
        runBlocking {
            val railData = FakeRailData(
                modeStationHandler = { mode ->
                    Failure(TransportError.MetadataUnavailable(mode.value))
                },
                lineRouteHandler = { lineId ->
                    Success(
                        RailLineRouteRecord(
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
            val lineGeometrySource = FakeRailLineGeometrySource {
                Failure(TransportError.MetadataUnavailable("missing geometry"))
            }
            val repository = RealRailLineMapRepository(railData, lineGeometrySource)

            val result = repository.getRailLineMap()

            expectThat(result).isFailure().isA<TransportError.MetadataUnavailable>()
        }
    }
}
