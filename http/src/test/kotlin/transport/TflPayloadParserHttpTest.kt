package transport

import kotlin.test.Test
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.get
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEqualTo

class TflPayloadParserHttpTest {
    private val tflPayloadParser: TflPayloadParser =
        TflPayloadParserHttp(transportJson())

    @Test
    fun `parseLineStations filters non metro stop points`() {
        val result = tflPayloadParser.parseLineStations(
            """
            [
              {"id":"940GZZLUGPK","naptanId":"940GZZLUGPK","commonName":"Green Park Underground Station","lat":51.506947,"lon":-0.142787,"stopType":"NaptanMetroStation"},
              {"id":"910GABWDXR","naptanId":"910GABWDXR","commonName":"Abbey Wood","lat":51.490719,"lon":0.121823,"stopType":"NaptanRailStation"},
              {"id":"4900ZZLUGPK1","naptanId":"4900ZZLUGPK1","commonName":"Green Park Station","lat":51.506947,"lon":-0.142787,"stopType":"NaptanMetroEntrance"}
            ]
            """.trimIndent(),
            LineId("victoria"),
            "/Line/victoria/StopPoints"
        )

        expectThat(result).isSuccess().hasSize(2)
        expectThat(result).isSuccess().get { first().stationId }.isEqualTo(StationId("940GZZLUGPK"))
        expectThat(result).isSuccess().get { first().name }.isEqualTo(StationName("Green Park Underground Station"))
    }

    @Test
    fun `parseLineRoute parses unique route geometry from line strings`() {
        val result = tflPayloadParser.parseLineRoute(
            """
            {
              "lineId":"victoria",
              "lineName":"Victoria",
              "lineStrings":[
                "[[[-0.019885,51.582965],[-0.04115,51.586919]]]",
                "[[[-0.04115,51.586919],[-0.019885,51.582965]]]"
              ],
              "stopPointSequences":[
                {
                  "direction":"outbound",
                  "stopPoint":[
                    {"id":"940GZZLUWWL","name":"Walthamstow Central Underground Station","lat":51.582965,"lon":-0.019885,"stopType":"NaptanMetroStation"},
                    {"id":"940GZZLUBLR","name":"Blackhorse Road Underground Station","lat":51.586919,"lon":-0.04115,"stopType":"NaptanMetroStation"}
                  ]
                }
              ]
            }
            """.trimIndent(),
            "/Line/victoria/Route/Sequence/all"
        )

        expectThat(result).isSuccess().get { lineId }.isEqualTo(LineId("victoria"))
        expectThat(result).isSuccess().get { paths }.hasSize(1)
        expectThat(result).isSuccess().get { sequences }.hasSize(1)
        expectThat(result).isSuccess().get { sequences.first().direction }.isEqualTo(TrainDirection("outbound"))
        expectThat(result).isSuccess().get { paths.first().coordinates.first() }.isEqualTo(GeoCoordinate(51.582965, -0.019885))
    }

    @Test
    fun `parseLineRoute returns payload failure for invalid coordinate pairs`() {
        val result = tflPayloadParser.parseLineRoute(
            """
            {
              "lineId":"victoria",
              "lineName":"Victoria",
              "lineStrings":[
                "[[[-0.019885]]]"
              ],
              "stopPointSequences":[
                {
                  "direction":"outbound",
                  "stopPoint":[
                    {"id":"940GZZLUWWL","name":"Walthamstow Central Underground Station","lat":51.582965,"lon":-0.019885,"stopType":"NaptanMetroStation"}
                  ]
                }
              ]
            }
            """.trimIndent(),
            "/Line/victoria/Route/Sequence/all"
        )

        expectThat(result)
            .isFailure()
            .isA<TransportError.UpstreamPayloadFailure>()
            .get(TransportError.UpstreamPayloadFailure::message)
            .contains("invalid line coordinate")
    }

    @Test
    fun `parsePredictions parses live arrival payload`() {
        val result = tflPayloadParser.parsePredictions(
            """
            [
              {
                "id":"-303938351",
                "vehicleId":"257",
                "naptanId":"940GZZLUGPK",
                "stationName":"Green Park Underground Station",
                "lineId":"victoria",
                "lineName":"Victoria",
                "platformName":"Northbound - Platform 4",
                "direction":"outbound",
                "destinationNaptanId":"940GZZLUWWL",
                "destinationName":"Walthamstow Central Underground Station",
                "timestamp":"2026-03-22T00:49:20Z",
                "timeToStation":120,
                "currentLocation":"Approaching Green Park",
                "towards":"Walthamstow Central",
                "expectedArrival":"2026-03-22T00:51:20Z",
                "timeToLive":"2026-03-22T00:51:20Z",
                "modeName":"tube"
              }
            ]
            """.trimIndent(),
            "/StopPoint/940GZZLUGPK/Arrivals"
        )

        expectThat(result).isSuccess().hasSize(1)
        expectThat(result).isSuccess().get { first().vehicleId }.isEqualTo(VehicleId("257"))
        expectThat(result).isSuccess().get { first().currentLocation }.isEqualTo(LocationDescription("Approaching Green Park"))
    }

    @Test
    fun `parsePredictions allows missing optional direction and destination fields`() {
        val result = tflPayloadParser.parsePredictions(
            """
            [
              {
                "id":"-303938351",
                "vehicleId":"257",
                "naptanId":"940GZZLUGPK",
                "stationName":"Green Park Underground Station",
                "lineId":"victoria",
                "lineName":"Victoria",
                "platformName":"Northbound - Platform 4",
                "timestamp":"2026-03-22T00:49:20Z",
                "timeToStation":120,
                "currentLocation":"Approaching Green Park",
                "towards":"Walthamstow Central",
                "expectedArrival":"2026-03-22T00:51:20Z",
                "timeToLive":"2026-03-22T00:51:20Z",
                "modeName":"tube"
              }
            ]
            """.trimIndent(),
            "/StopPoint/940GZZLUGPK/Arrivals"
        )

        expectThat(result).isSuccess().hasSize(1)
        expectThat(result).isSuccess().get { first().direction }.isEqualTo(null)
        expectThat(result).isSuccess().get { first().destinationName }.isEqualTo(null)
    }

    @Test
    fun `parsePredictions allows missing optional timeToStation`() {
        val result = tflPayloadParser.parsePredictions(
            """
            [
              {
                "id":"-303938351",
                "vehicleId":"257",
                "naptanId":"940GZZLUGPK",
                "stationName":"Green Park Underground Station",
                "lineId":"victoria",
                "lineName":"Victoria",
                "platformName":"Northbound - Platform 4",
                "timestamp":"2026-03-22T00:49:20Z",
                "currentLocation":"At Platform",
                "towards":"Walthamstow Central",
                "expectedArrival":"2026-03-22T00:51:20Z",
                "timeToLive":"2026-03-22T00:51:20Z",
                "modeName":"tube"
              }
            ]
            """.trimIndent(),
            "/Mode/tube/Arrivals"
        )

        expectThat(result).isSuccess().hasSize(1)
        expectThat(result).isSuccess().get { first().secondsToNextStop }.isEqualTo(null)
    }

    @Test
    fun `parsePredictions treats blank non tube fields as missing values`() {
        val result = tflPayloadParser.parsePredictions(
            """
            [
              {
                "id":"-660031888",
                "vehicleId":"",
                "naptanId":"910GHTRWTM5",
                "stationName":"Heathrow Terminal 5 Rail Station",
                "lineId":"elizabeth",
                "lineName":"Elizabeth line",
                "platformName":"3",
                "direction":"",
                "destinationName":"Abbey Wood",
                "timestamp":"2026-03-22T16:00:00Z",
                "currentLocation":"",
                "towards":"",
                "expectedArrival":"2026-03-22T16:08:00Z",
                "timeToLive":"2026-03-22T16:08:00Z",
                "modeName":"elizabeth-line"
              }
            ]
            """.trimIndent(),
            "/Mode/elizabeth-line/Arrivals"
        )

        expectThat(result).isSuccess().hasSize(1)
        expectThat(result).isSuccess().get { first().vehicleId }.isEqualTo(null)
        expectThat(result).isSuccess().get { first().direction }.isEqualTo(null)
        expectThat(result).isSuccess().get { first().currentLocation }.isEqualTo(null)
        expectThat(result).isSuccess().get { first().towards }.isEqualTo(null)
        expectThat(result).isSuccess().get { first().modeName }.isEqualTo(TransportModeName("elizabeth-line"))
    }

    @Test
    fun `parsePredictions allows missing tram current location`() {
        val result = tflPayloadParser.parsePredictions(
            """
            [
              {
                "id":"-2131546322",
                "vehicleId":"2531",
                "naptanId":"940GZZCRWCR",
                "stationName":"West Croydon Tram Stop",
                "lineId":"tram",
                "lineName":"Tram",
                "platformName":"Westbound - Platform 2",
                "direction":"outbound",
                "destinationName":"West Croydon",
                "timestamp":"2026-03-22T16:31:56Z",
                "towards":"West Croydon",
                "expectedArrival":"2026-03-22T16:32:00Z",
                "timeToLive":"2026-03-22T16:32:00Z",
                "modeName":"tram"
              }
            ]
            """.trimIndent(),
            "/Mode/tram/Arrivals"
        )

        expectThat(result).isSuccess().hasSize(1)
        expectThat(result).isSuccess().get { first().currentLocation }.isEqualTo(null)
        expectThat(result).isSuccess().get { first().modeName }.isEqualTo(TransportModeName("tram"))
    }

    @Test
    fun `parsePredictions returns payload failure for invalid timestamps`() {
        val result = tflPayloadParser.parsePredictions(
            """
            [
              {
                "id":"-303938351",
                "vehicleId":"257",
                "naptanId":"940GZZLUGPK",
                "stationName":"Green Park Underground Station",
                "lineId":"victoria",
                "lineName":"Victoria",
                "platformName":"Northbound - Platform 4",
                "timestamp":"not-an-instant",
                "timeToStation":120,
                "currentLocation":"Approaching Green Park",
                "towards":"Walthamstow Central",
                "expectedArrival":"2026-03-22T00:51:20Z",
                "timeToLive":"2026-03-22T00:51:20Z",
                "modeName":"tube"
              }
            ]
            """.trimIndent(),
            "/StopPoint/940GZZLUGPK/Arrivals"
        )

        expectThat(result)
            .isFailure()
            .isA<TransportError.UpstreamPayloadFailure>()
            .get(TransportError.UpstreamPayloadFailure::message)
            .contains("Invalid instant field 'timestamp'")
    }

    @Test
    fun `parsePredictions returns payload failure when a required field is missing`() {
        val result = tflPayloadParser.parsePredictions(
            """
            [
              {
                "id":"-303938351",
                "vehicleId":"257",
                "naptanId":"940GZZLUGPK",
                "stationName":"Green Park Underground Station",
                "lineName":"Victoria",
                "platformName":"Northbound - Platform 4",
                "timestamp":"2026-03-22T00:49:20Z",
                "timeToStation":120,
                "currentLocation":"Approaching Green Park",
                "towards":"Walthamstow Central",
                "expectedArrival":"2026-03-22T00:51:20Z",
                "timeToLive":"2026-03-22T00:51:20Z",
                "modeName":"tube"
              }
            ]
            """.trimIndent(),
            "/StopPoint/940GZZLUGPK/Arrivals"
        )

        expectThat(result)
            .isFailure()
            .isA<TransportError.UpstreamPayloadFailure>()
            .get(TransportError.UpstreamPayloadFailure::message)
            .contains("lineId")
    }
}
