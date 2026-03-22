package transport

import dev.forkhandles.result4k.Success
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.hasSize

class RealTubeLineMapServiceTest {
    @Test
    fun `getTubeLineMap delegates to the repository`() {
        runBlocking {
            val service = RealTubeLineMapService(
                StubTubeLineMapRepository(
                    Success(
                        TubeLineMap(
                            listOf(
                                TubeLine(
                                    LineId("victoria"),
                                    LineName("Victoria"),
                                    listOf(
                                        TubeLinePath(
                                            listOf(
                                                GeoCoordinate(51.0, -0.1),
                                                GeoCoordinate(51.1, -0.2)
                                            )
                                        )
                                    ),
                                    emptyList()
                                )
                            )
                        )
                    )
                )
            )

            val result = service.getTubeLineMap()

            expectThat(result).isSuccess().get { lines }.hasSize(1)
        }
    }
}

private class StubTubeLineMapRepository(
    private val result: TransportResult<TubeLineMap>
) : TubeLineMapRepository {
    override suspend fun getTubeLineMap() =
        result
}
