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
    fun `assemble deduplicates the same service across station boards`() {
        val predictions = listOf(
            RailPredictionRecord(
                VehicleId("257"),
                StationId("940GZZLUKSX"),
                StationName("King's Cross St. Pancras Underground Station"),
                LineId("victoria"),
                LineName("Victoria"),
                ServiceDirection("outbound"),
                DestinationName("Walthamstow Central Underground Station"),
                Instant.parse("2026-03-22T00:49:20Z"),
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
                ServiceDirection("outbound"),
                DestinationName("Walthamstow Central Underground Station"),
                Instant.parse("2026-03-22T00:49:20Z"),
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

        expectThat(snapshot.services).hasSize(1)
        expectThat(snapshot.services.first().serviceId).isEqualTo(ServiceId("victoria:257"))
        expectThat(snapshot.services.first().location.type).isEqualTo(LocationType.STATION_BOARD)
        expectThat(snapshot.services.first().nextStop!!.id).isEqualTo(StationId("940GZZLUWSM"))
        expectThat(snapshot.services.first().sourcePredictions).isEqualTo(PredictionCount(2))
        expectThat(snapshot.services.first().futureArrivals).hasSize(2)
        expectThat(snapshot.services.first().futureArrivals.first().stationName).isEqualTo(StationName("Warren Street Underground Station"))
        expectThat(snapshot.services.first().futureArrivals.last().stationName).isEqualTo(StationName("King's Cross St. Pancras Underground Station"))
    }

    @Test
    fun `assemble separates tube predictions that share a vehicle id across different lines`() {
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

        expectThat(snapshot.services).hasSize(2)
        expectThat(snapshot.services.map(LiveRailService::serviceId)).isEqualTo(
            listOf(
                ServiceId("hammersmith-city:175"),
                ServiceId("metropolitan:175")
            )
        )
    }

    @Test
    fun `assemble separates non tube predictions that share a vehicle id across different lines`() {
        val predictions = listOf(
            RailPredictionRecord(
                VehicleId("202603298053838"),
                null,
                StationName("Clapham Junction Rail Station"),
                LineId("windrush"),
                LineName("Windrush"),
                null,
                DestinationName("Highbury & Islington Rail Station"),
                Instant.parse("2026-03-22T00:49:20Z"),
                LocationDescription("Clapham Junction Rail Station"),
                null,
                Instant.parse("2026-03-22T00:51:31Z"),
                TransportModeName("overground")
            ),
            RailPredictionRecord(
                VehicleId("202603298053838"),
                null,
                StationName("Dalston Junction Rail Station"),
                LineId("mildmay"),
                LineName("Mildmay"),
                null,
                DestinationName("Stratford (London) Rail Station"),
                Instant.parse("2026-03-22T00:49:20Z"),
                LocationDescription("Dalston Junction Rail Station"),
                null,
                Instant.parse("2026-03-22T00:51:32Z"),
                TransportModeName("overground")
            )
        )

        val snapshot = assembler.assemble(
            railNetwork,
            predictions,
            Instant.parse("2026-03-22T00:49:20Z"),
            StationQueryCount(1),
            StationFailureCount(0)
        )

        expectThat(snapshot.services).hasSize(2)
        expectThat(snapshot.services.map(LiveRailService::serviceId)).isEqualTo(
            listOf(
                ServiceId("mildmay:202603298053838"),
                ServiceId("windrush:202603298053838")
            )
        )
    }

    @Test
    fun `assemble keeps expected arrival when upstream only provides absolute station time`() {
        val snapshot = assembler.assemble(
            railNetwork,
            listOf(
                RailPredictionRecord(
                    VehicleId("257"),
                    StationId("940GZZLUKSX"),
                    StationName("King's Cross St. Pancras Underground Station"),
                    LineId("victoria"),
                    LineName("Victoria"),
                    ServiceDirection("outbound"),
                    DestinationName("Walthamstow Central Underground Station"),
                    Instant.parse("2026-03-22T00:49:20Z"),
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

        expectThat(snapshot.services).hasSize(1)
        expectThat(snapshot.services.first().expectedArrival).isEqualTo(Instant.parse("2026-03-22T00:51:20Z"))
    }

    @Test
    fun `assemble includes supported rail predictions with sparse location fields`() {
        val snapshot = assembler.assemble(
            railNetwork,
            listOf(
                RailPredictionRecord(
                    VehicleId("972"),
                    StationId("910GABWDXR"),
                    StationName("Abbey Wood"),
                    LineId("elizabeth"),
                    LineName("Elizabeth line"),
                    null,
                    DestinationName("Heathrow Terminal 5"),
                    Instant.parse("2026-03-22T16:00:00Z"),
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

        expectThat(snapshot.services).hasSize(1)
        expectThat(snapshot.services.first().lineIds).contains(LineId("elizabeth"))
        expectThat(snapshot.services.first().currentLocation).isEqualTo(LocationDescription("Abbey Wood"))
        expectThat(snapshot.services.first().nextStop).isNotNull().get { id }.isEqualTo(StationId("910GABWDXR"))
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
                    ServiceDirection("outbound"),
                    DestinationName("West Croydon"),
                    Instant.parse("2026-03-22T16:31:56Z"),
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

        expectThat(snapshot.services).hasSize(1)
        expectThat(snapshot.services.first().lineIds).contains(LineId("tram"))
        expectThat(snapshot.services.first().currentLocation).isEqualTo(LocationDescription("West Croydon Tram Stop"))
        expectThat(snapshot.services.first().location.type).isEqualTo(LocationType.STATION_BOARD)
    }
}
