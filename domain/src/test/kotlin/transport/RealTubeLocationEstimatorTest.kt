package transport

import kotlin.test.Test
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull

class RealTubeLocationEstimatorTest {
    private val tubeNetwork = testTubeNetwork(
        listOf(
            testStation("940GZZLUVIC", "Victoria Underground Station", 51.496359, -0.143102, setOf("victoria")),
            testStation("940GZZLUGPK", "Green Park Underground Station", 51.506947, -0.142787, setOf("victoria")),
            testStation("940GZZLUKSX", "King's Cross St. Pancras Underground Station", 51.530663, -0.123194, setOf("victoria"))
        )
    )
    private val estimator: TubeLocationEstimator = RealTubeLocationEstimator()

    @Test
    fun `estimateLocation maps between stations to midpoint`() {
        val location = estimator.estimateLocation(
            tubeNetwork,
            setOf(LineId("victoria")),
            LocationDescription("Between Victoria and Green Park"),
            null
        )

        expectThat(location.type).isEqualTo(LocationType.BETWEEN_STATIONS)
        expectThat(location.fromStation).isNotNull().get { id }.isEqualTo(StationId("940GZZLUVIC"))
        expectThat(location.toStation).isNotNull().get { id }.isEqualTo(StationId("940GZZLUGPK"))
        expectThat(location.coordinate).isNotNull().get { lat }.isEqualTo((51.496359 + 51.506947) / 2)
    }

    @Test
    fun `estimateLocation maps at station location text`() {
        val location = estimator.estimateLocation(
            tubeNetwork,
            setOf(LineId("victoria")),
            LocationDescription("At King's Cross St. Pancras Platform 4"),
            null
        )

        expectThat(location.type).isEqualTo(LocationType.AT_STATION)
        expectThat(location.station).isNotNull().get { id }.isEqualTo(StationId("940GZZLUKSX"))
    }

    @Test
    fun `estimateLocation falls back to board station when location text is blank`() {
        val boardStation = tubeNetwork.stationsById[StationId("940GZZLUGPK")]!!
        val location = estimator.estimateLocation(tubeNetwork, setOf(LineId("victoria")), null, boardStation)

        expectThat(location.type).isEqualTo(LocationType.STATION_BOARD)
        expectThat(location.station).isNotNull().get { id }.isEqualTo(StationId("940GZZLUGPK"))
    }

    @Test
    fun `estimateLocation returns unknown for unmatched text`() {
        val location = estimator.estimateLocation(
            tubeNetwork,
            setOf(LineId("victoria")),
            LocationDescription("Check Front of Train"),
            null
        )

        expectThat(location.type).isEqualTo(LocationType.UNKNOWN)
    }
}
