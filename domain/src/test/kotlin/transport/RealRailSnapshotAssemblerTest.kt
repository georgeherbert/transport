package transport

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.get
import strikt.assertions.hasSize
import strikt.assertions.isNotNull
import strikt.assertions.isEqualTo

class RealRailSnapshotAssemblerTest {
    private val railNetwork = testRailNetwork(
        listOf(
            testStation("940GZZLUVIC", "Victoria Underground Station", 51.496359, -0.143102, setOf("victoria")),
            testStation("940GZZLUGPK", "Green Park Underground Station", 51.506947, -0.142787, setOf("victoria")),
            testStation("940GZZLUWSM", "Warren Street Underground Station", 51.524951, -0.138321, setOf("victoria")),
            testStation("940GZZLUKSX", "King's Cross St. Pancras Underground Station", 51.530663, -0.123194, setOf("victoria")),
            testStation("940GZZLUEUS", "Euston Square Underground Station", 51.525604, -0.135829, setOf("metropolitan", "hammersmith-city")),
            testStation("910GABWDXR", "Abbey Wood", 51.490719, 0.121823, setOf("elizabeth")),
            testStation("940GZZCRWCR", "West Croydon Tram Stop", 51.378785, -0.102882, setOf("tram"))
        )
    )
    private val railLocationEstimator = StubRailLocationEstimator()
    private val assembler: RailSnapshotAssembler = RealRailSnapshotAssembler(railLocationEstimator)

    @Test
    fun `assemble deduplicates the same train across station boards`() {
        val predictions = listOf(
            RailPredictionRecord(
                VehicleId("257"),
                StationId("940GZZLUKSX"),
                StationName("King's Cross St. Pancras Underground Station"),
                LineId("victoria"),
                LineName("Victoria"),
                TrainDirection("outbound"),
                DestinationName("Walthamstow Central Underground Station"),
                Instant.parse("2026-03-22T00:49:20Z"),
                Duration.ofSeconds(492),
                LocationDescription("Between Victoria and Green Park"),
                TowardsDescription("Walthamstow Central"),
                Instant.parse("2026-03-22T00:57:32Z"),
                TransportModeName("tube")
            ),
            RailPredictionRecord(
                VehicleId("257"),
                StationId("940GZZLUWSM"),
                StationName("Warren Street Underground Station"),
                LineId("victoria"),
                LineName("Victoria"),
                TrainDirection("outbound"),
                DestinationName("Walthamstow Central Underground Station"),
                Instant.parse("2026-03-22T00:49:20Z"),
                Duration.ofSeconds(120),
                LocationDescription("Approaching Warren Street"),
                TowardsDescription("Walthamstow Central"),
                Instant.parse("2026-03-22T00:51:20Z"),
                TransportModeName("tube")
            )
        )

        val snapshot = assembler.assemble(
            railNetwork,
            predictions,
            Instant.parse("2026-03-22T00:49:20Z"),
            StationQueryCount(2),
            StationFailureCount(0)
        )

        expectThat(snapshot.trains).hasSize(1)
        expectThat(snapshot.trains.first().trainId).isEqualTo(TrainId("victoria|257"))
        expectThat(snapshot.trains.first().location.type).isEqualTo(LocationType.STATION_BOARD)
        expectThat(snapshot.trains.first().nextStop!!.id).isEqualTo(StationId("940GZZLUWSM"))
        expectThat(snapshot.trains.first().sourcePredictions).isEqualTo(PredictionCount(2))
    }

    @Test
    fun `assemble separates reused vehicle ids across different lines`() {
        val predictions = listOf(
            RailPredictionRecord(
                VehicleId("175"),
                StationId("940GZZLUEUS"),
                StationName("Euston Square Underground Station"),
                LineId("hammersmith-city"),
                LineName("Hammersmith & City"),
                null,
                DestinationName("Check Front of Train"),
                Instant.parse("2026-03-22T00:49:20Z"),
                Duration.ofSeconds(132),
                LocationDescription("At Euston Square Platform 2"),
                TowardsDescription("Check Front of Train"),
                Instant.parse("2026-03-22T00:51:32Z"),
                TransportModeName("tube")
            ),
            RailPredictionRecord(
                VehicleId("175"),
                StationId("940GZZLUEUS"),
                StationName("Euston Square Underground Station"),
                LineId("metropolitan"),
                LineName("Metropolitan"),
                null,
                DestinationName("Check Front of Train"),
                Instant.parse("2026-03-22T00:49:20Z"),
                Duration.ofSeconds(131),
                LocationDescription("At Euston Square Platform 2"),
                TowardsDescription("Check Front of Train"),
                Instant.parse("2026-03-22T00:51:31Z"),
                TransportModeName("tube")
            )
        )

        val snapshot = assembler.assemble(
            railNetwork,
            predictions,
            Instant.parse("2026-03-22T00:49:20Z"),
            StationQueryCount(1),
            StationFailureCount(0)
        )

        expectThat(snapshot.trains).hasSize(2)
        expectThat(snapshot.trains.map(LiveRailTrain::trainId)).contains(
            TrainId("hammersmith-city|175"),
            TrainId("metropolitan|175")
        )
        expectThat(snapshot.trains.map(LiveRailTrain::lineIds)).contains(
            listOf(LineId("hammersmith-city")),
            listOf(LineId("metropolitan"))
        )
    }

    @Test
    fun `assemble leaves seconds to next stop empty when timeToStation is missing`() {
        val snapshot = assembler.assemble(
            railNetwork,
            listOf(
                RailPredictionRecord(
                    VehicleId("257"),
                    StationId("940GZZLUKSX"),
                    StationName("King's Cross St. Pancras Underground Station"),
                    LineId("victoria"),
                    LineName("Victoria"),
                    TrainDirection("outbound"),
                    DestinationName("Walthamstow Central Underground Station"),
                    Instant.parse("2026-03-22T00:49:20Z"),
                    null,
                    LocationDescription("Approaching Warren Street"),
                    TowardsDescription("Walthamstow Central"),
                    Instant.parse("2026-03-22T00:51:20Z"),
                    TransportModeName("tube")
                )
            ),
            Instant.parse("2026-03-22T00:49:50Z"),
            StationQueryCount(1),
            StationFailureCount(0)
        )

        expectThat(snapshot.trains).hasSize(1)
        expectThat(snapshot.trains.first().secondsToNextStop).isEqualTo(null)
    }

    @Test
    fun `assemble includes supported rail predictions with sparse location fields`() {
        val snapshot = assembler.assemble(
            railNetwork,
            listOf(
                RailPredictionRecord(
                    null,
                    StationId("910GABWDXR"),
                    StationName("Abbey Wood"),
                    LineId("elizabeth"),
                    LineName("Elizabeth line"),
                    null,
                    DestinationName("Heathrow Terminal 5"),
                    Instant.parse("2026-03-22T16:00:00Z"),
                    null,
                    null,
                    null,
                    Instant.parse("2026-03-22T16:08:00Z"),
                    TransportModeName("elizabeth-line")
                )
            ),
            Instant.parse("2026-03-22T16:00:30Z"),
            StationQueryCount(1),
            StationFailureCount(0)
        )

        expectThat(snapshot.trains).hasSize(1)
        expectThat(snapshot.trains.first().lineIds).contains(LineId("elizabeth"))
        expectThat(snapshot.trains.first().currentLocation).isEqualTo(LocationDescription("Abbey Wood"))
        expectThat(snapshot.trains.first().nextStop).isNotNull().get { id }.isEqualTo(StationId("910GABWDXR"))
    }

    @Test
    fun `assemble includes tram predictions when current location is missing`() {
        val snapshot = assembler.assemble(
            railNetwork,
            listOf(
                RailPredictionRecord(
                    VehicleId("2531"),
                    StationId("940GZZCRWCR"),
                    StationName("West Croydon Tram Stop"),
                    LineId("tram"),
                    LineName("Tram"),
                    TrainDirection("outbound"),
                    DestinationName("West Croydon"),
                    Instant.parse("2026-03-22T16:31:56Z"),
                    null,
                    null,
                    TowardsDescription("West Croydon"),
                    Instant.parse("2026-03-22T16:32:00Z"),
                    TransportModeName("tram")
                )
            ),
            Instant.parse("2026-03-22T16:31:56Z"),
            StationQueryCount(1),
            StationFailureCount(0)
        )

        expectThat(snapshot.trains).hasSize(1)
        expectThat(snapshot.trains.first().lineIds).contains(LineId("tram"))
        expectThat(snapshot.trains.first().currentLocation).isEqualTo(LocationDescription("West Croydon Tram Stop"))
        expectThat(snapshot.trains.first().location.type).isEqualTo(LocationType.STATION_BOARD)
    }
}
