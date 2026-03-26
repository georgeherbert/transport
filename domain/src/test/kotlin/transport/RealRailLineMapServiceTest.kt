package transport

import dev.forkhandles.result4k.Success
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.hasSize

class RealRailLineMapServiceTest {
    @Test
    fun `getRailLineMap delegates to the repository`() {
        runBlocking {
            val service = RealRailLineMapService(
                StubRailLineMapRepository(
                    Success(
                        RailLineMap(
                            listOf(
                                RailLine(
                                    LineId("victoria"),
                                    LineName("Victoria"),
                                    listOf(
                                        RailLinePath(
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

            val result = service.getRailLineMap()

            expectThat(result).isSuccess().get { lines }.hasSize(1)
        }
    }
}
