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

class RealTubeMetadataRepositoryTest {
    @Test
    fun `getTubeNetwork merges shared stations across lines`() {
        runBlocking {
            val tubeData = FakeTubeData(
                lineStationHandler = { lineId ->
                    when (lineId) {
                        LineId("victoria") ->
                            Success(
                                listOf(
                                    TubeStationRecord(
                                        StationId("940GZZLUKSX"),
                                        StationName("King's Cross St. Pancras Underground Station"),
                                        GeoCoordinate(51.530663, -0.123194),
                                        lineId
                                    )
                                )
                            )
                        LineId("circle") ->
                            Success(
                                listOf(
                                    TubeStationRecord(
                                        StationId("940GZZLUKSX"),
                                        StationName("King's Cross St. Pancras Underground Station"),
                                        GeoCoordinate(51.530663, -0.123194),
                                        lineId
                                    ),
                                    TubeStationRecord(
                                        StationId("940GZZLUGPK"),
                                        StationName("Green Park Underground Station"),
                                        GeoCoordinate(51.506947, -0.142787),
                                        lineId
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
            val repository = RealTubeMetadataRepository(tubeData)

            val result = repository.getTubeNetwork()

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
    fun `getTubeNetwork reuses cached network`() {
        runBlocking {
            val requests = AtomicInteger(0)
            val tubeData = FakeTubeData(
                lineStationHandler = { lineId ->
                    requests.incrementAndGet()
                    if (lineId == LineId("victoria")) {
                        Success(
                            listOf(
                                TubeStationRecord(
                                    StationId("940GZZLUGPK"),
                                    StationName("Green Park Underground Station"),
                                    GeoCoordinate(51.506947, -0.142787),
                                    lineId
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
            val repository = RealTubeMetadataRepository(tubeData)

            expectThat(repository.getTubeNetwork()).isSuccess()
            expectThat(repository.getTubeNetwork()).isSuccess()

            expectThat(requests.get()).isEqualTo(supportedRailLineIds.size)
        }
    }

    @Test
    fun `getTubeNetwork returns failure when upstream metadata call fails`() {
        runBlocking {
            val tubeData = FakeTubeData(
                lineStationHandler = { lineId ->
                    if (lineId == supportedRailLineIds.first()) {
                        Failure(TransportError.UpstreamNetworkFailure("/Line/${lineId.value}/StopPoints", "boom"))
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
            val repository = RealTubeMetadataRepository(tubeData)

            val result = repository.getTubeNetwork()

            expectThat(result).isFailure().isA<TransportError.UpstreamNetworkFailure>()
        }
    }
}
