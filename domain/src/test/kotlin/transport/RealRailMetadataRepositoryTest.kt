package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.get
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEqualTo

class RealRailMetadataRepositoryTest {
    @Test
    fun `getRailNetwork merges shared stations across lines`() {
        runBlocking {
            val railData = FakeRailData(
                modeStationHandler = { mode ->
                    when (mode) {
                        TransportModeName("tube") ->
                            Success(
                                listOf(
                                    RailStationRecord(
                                        StationId("940GZZLUKSX"),
                                        StationName("King's Cross St. Pancras Underground Station"),
                                        GeoCoordinate(51.530663, -0.123194),
                                        LineId("victoria")
                                    ),
                                    RailStationRecord(
                                        StationId("940GZZLUKSX"),
                                        StationName("King's Cross St. Pancras Underground Station"),
                                        GeoCoordinate(51.530663, -0.123194),
                                        LineId("circle")
                                    ),
                                    RailStationRecord(
                                        StationId("940GZZLUGPK"),
                                        StationName("Green Park Underground Station"),
                                        GeoCoordinate(51.506947, -0.142787),
                                        LineId("circle")
                                    )
                                )
                            )
                        else -> Success(emptyList())
                    }
                },
                lineRouteHandler = { lineId ->
                    Failure(TransportError.MetadataUnavailable(lineId.value))
                },
                predictionHandler = { mode ->
                    Failure(TransportError.SnapshotUnavailable("unused"))
                }
            )
            val repository = RealRailMetadataRepository(railData)

            val result = repository.getRailNetwork()

            expectThat(result).isSuccess()
            expectThat(result).isSuccess().get { stationsById }.hasSize(2)
            expectThat(result)
                .isSuccess()
                .get { stationsById[StationId("940GZZLUKSX")]!!.lineIds }
                .contains(LineId("victoria"), LineId("circle"))
            expectThat(result)
                .isSuccess()
                .get { aliases["kings cross st pancras"]!! }
                .hasSize(1)
        }
    }

    @Test
    fun `getRailNetwork reuses cached network`() {
        runBlocking {
            val requests = AtomicInteger(0)
            val railData = FakeRailData(
                modeStationHandler = { mode ->
                    requests.incrementAndGet()
                    if (mode == TransportModeName("tube")) {
                        Success(
                            listOf(
                                RailStationRecord(
                                    StationId("940GZZLUGPK"),
                                    StationName("Green Park Underground Station"),
                                    GeoCoordinate(51.506947, -0.142787),
                                    LineId("victoria")
                                )
                            )
                        )
                    } else {
                        Success(emptyList())
                    }
                },
                lineRouteHandler = { lineId ->
                    Failure(TransportError.MetadataUnavailable(lineId.value))
                },
                predictionHandler = { mode ->
                    Failure(TransportError.SnapshotUnavailable("unused"))
                }
            )
            val repository = RealRailMetadataRepository(railData)

            expectThat(repository.getRailNetwork()).isSuccess()
            expectThat(repository.getRailNetwork()).isSuccess()

            expectThat(requests.get()).isEqualTo(supportedRailModes.size)
        }
    }

    @Test
    fun `getRailNetwork returns failure when upstream metadata call fails`() {
        runBlocking {
            val railData = FakeRailData(
                modeStationHandler = { mode ->
                    if (mode == supportedRailModes.first()) {
                        Failure(TransportError.UpstreamNetworkFailure("/StopPoint/Mode/${mode.value}", "boom"))
                    } else {
                        Success(emptyList())
                    }
                },
                lineRouteHandler = { lineId ->
                    Failure(TransportError.MetadataUnavailable(lineId.value))
                },
                predictionHandler = { mode ->
                    Failure(TransportError.SnapshotUnavailable("unused"))
                }
            )
            val repository = RealRailMetadataRepository(railData)

            val result = repository.getRailNetwork()

            expectThat(result).isFailure().isA<TransportError.UpstreamNetworkFailure>()
        }
    }
}
