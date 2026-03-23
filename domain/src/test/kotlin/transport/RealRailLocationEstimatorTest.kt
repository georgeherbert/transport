package transport

import kotlin.test.Test
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull

class RealRailLocationEstimatorTest {
    private val railNetwork = testRailNetwork(
        listOf(
            testStation("940GZZLUVIC", "Victoria Underground Station", 51.496359, -0.143102, setOf("victoria")),
            testStation("940GZZLUGPK", "Green Park Underground Station", 51.506947, -0.142787, setOf("victoria"))
        )
    )
    private val estimator: RailLocationEstimator = RealRailLocationEstimator()

    @Test
    fun `estimateLocation uses the canonical next stop as the anchor when it exists`() {
        val boardStation = railNetwork.stationsById[StationId("940GZZLUGPK")]!!

        val location = estimator.estimateLocation(
            LocationDescription("Between Victoria and Green Park"),
            boardStation
        )

        expectThat(location.type).isEqualTo(LocationType.STATION_BOARD)
        expectThat(location.description).isEqualTo(LocationDescription("Between Victoria and Green Park"))
        expectThat(location.station).isNotNull().get { id }.isEqualTo(StationId("940GZZLUGPK"))
        expectThat(location.coordinate).isNotNull().get { lat }.isEqualTo(51.506947)
    }

    @Test
    fun `estimateLocation ignores currentLocation text when canonical next stop is absent`() {
        val location = estimator.estimateLocation(
            LocationDescription("At Victoria Platform 1"),
            null
        )

        expectThat(location.type).isEqualTo(LocationType.UNKNOWN)
        expectThat(location.description).isEqualTo(LocationDescription("At Victoria Platform 1"))
    }

    @Test
    fun `estimateLocation falls back to next stop name when no API description exists`() {
        val boardStation = railNetwork.stationsById[StationId("940GZZLUGPK")]!!

        val location = estimator.estimateLocation(null, boardStation)

        expectThat(location.type).isEqualTo(LocationType.STATION_BOARD)
        expectThat(location.description).isEqualTo(LocationDescription("Green Park Underground Station"))
    }
}
