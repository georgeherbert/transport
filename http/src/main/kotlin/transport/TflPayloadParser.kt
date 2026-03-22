package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.flatMap
import dev.forkhandles.result4k.map
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

interface TflPayloadParser {
    fun parseLineStations(body: String, lineId: LineId, endpoint: String): TransportResult<List<TubeStationRecord>>
    fun parsePredictions(body: String, endpoint: String): TransportResult<List<TubePredictionRecord>>
}

class TflPayloadParserHttp(
    private val json: Json
) : TflPayloadParser {
    override fun parseLineStations(body: String, lineId: LineId, endpoint: String) =
        decodeList<TflStopPointJson>(body, endpoint)
            .flatMap { stopPoints ->
                stopPoints
                    .map { stopPoint -> parseStopPoint(stopPoint, lineId, endpoint) }
                    .failFast()
                    .map { stationRecords -> stationRecords.flatten() }
            }

    override fun parsePredictions(body: String, endpoint: String) =
        decodeList<TflArrivalJson>(body, endpoint)
            .flatMap { arrivals ->
                arrivals
                    .map { arrival -> parseArrival(arrival, endpoint) }
                    .failFast()
            }

    private inline fun <reified T> decodeList(body: String, endpoint: String): TransportResult<List<T>> {
        return try {
            Success(json.decodeFromString<List<T>>(body))
        } catch (exception: SerializationException) {
            Failure(TransportError.UpstreamPayloadFailure(endpoint, exception.message ?: "Invalid JSON payload"))
        }
    }

    private fun parseStopPoint(
        stopPoint: TflStopPointJson,
        lineId: LineId,
        endpoint: String
    ): TransportResult<List<TubeStationRecord>> {
        if (stopPoint.stopType != "NaptanMetroStation") {
            return Success(emptyList())
        }

        return Success(
            listOf(
                TubeStationRecord(
                    StationId(stopPoint.naptanId),
                    StationName(stopPoint.commonName),
                    GeoCoordinate(stopPoint.lat, stopPoint.lon),
                    lineId
                )
            )
        )
    }

    private fun parseArrival(arrival: TflArrivalJson, endpoint: String): TransportResult<TubePredictionRecord> =
        parseInstant(arrival.timestamp, "timestamp", endpoint)
            .flatMap { observedAt ->
                parseInstant(arrival.expectedArrival, "expectedArrival", endpoint)
                    .map { expectedArrival ->
                        TubePredictionRecord(
                            VehicleId(arrival.vehicleId),
                            StationId(arrival.naptanId),
                            StationName(arrival.stationName),
                            LineId(arrival.lineId),
                            LineName(arrival.lineName),
                            arrival.direction.toValue(::TrainDirection),
                            arrival.destinationName.toValue(::DestinationName),
                            observedAt,
                            arrival.timeToStation?.let { timeToStation ->
                                Duration.ofSeconds(timeToStation.toLong())
                            },
                            LocationDescription(arrival.currentLocation),
                            TowardsDescription(arrival.towards),
                            expectedArrival,
                            TransportModeName(arrival.modeName)
                        )
                    }
            }

    private fun parseInstant(
        value: String,
        field: String,
        endpoint: String
    ): TransportResult<Instant> =
        try {
            Success(Instant.parse(value))
        } catch (exception: DateTimeParseException) {
            Failure(TransportError.UpstreamPayloadFailure(endpoint, "Invalid instant field '$field'"))
        }

    private fun <T> String?.toValue(factory: (String) -> T): T? =
        this
            ?.takeIf(String::isNotBlank)
            ?.let(factory)
}
