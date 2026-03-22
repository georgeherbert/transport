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
    fun parseLineRoute(body: String, endpoint: String): TransportResult<TubeLineRouteRecord>
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

    override fun parseLineRoute(body: String, endpoint: String) =
        decodeObject<TflRouteSequenceJson>(body, endpoint)
            .flatMap { routeSequence ->
                routeSequence.lineStrings
                    .map { lineString -> parseLineString(lineString, endpoint) }
                    .failFast()
                    .map(List<List<TubeLinePathRecord>>::flatten)
                    .map(::deduplicatePaths)
                    .map { paths ->
                        TubeLineRouteRecord(
                            LineId(routeSequence.lineId),
                            LineName(routeSequence.lineName),
                            paths
                        )
                    }
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

    private inline fun <reified T> decodeObject(body: String, endpoint: String): TransportResult<T> {
        return try {
            Success(json.decodeFromString<T>(body))
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

    private fun parseLineString(lineString: String, endpoint: String): TransportResult<List<TubeLinePathRecord>> =
        decodeObject<List<List<List<Double>>>>(lineString, endpoint)
            .flatMap { coordinateGroups ->
                coordinateGroups
                    .map { coordinates -> parseLinePath(coordinates, endpoint) }
                    .failFast()
            }

    private fun parseLinePath(
        coordinates: List<List<Double>>,
        endpoint: String
    ): TransportResult<TubeLinePathRecord> =
        coordinates
            .map { coordinate -> parseCoordinate(coordinate, endpoint) }
            .failFast()
            .flatMap { parsedCoordinates ->
                if (parsedCoordinates.isEmpty()) {
                    Failure(TransportError.UpstreamPayloadFailure(endpoint, "TfL returned an empty line path."))
                } else {
                    Success(TubeLinePathRecord(parsedCoordinates))
                }
            }

    private fun parseCoordinate(coordinate: List<Double>, endpoint: String): TransportResult<GeoCoordinate> =
        if (coordinate.size < 2) {
            Failure(TransportError.UpstreamPayloadFailure(endpoint, "TfL returned an invalid line coordinate."))
        } else {
            Success(GeoCoordinate(coordinate[1], coordinate[0]))
        }

    private fun deduplicatePaths(paths: List<TubeLinePathRecord>) =
        paths.distinctBy(::pathKey)

    private fun pathKey(path: TubeLinePathRecord): String {
        val forward = path.coordinates.joinToString(";") { coordinate ->
            "${coordinate.lat},${coordinate.lon}"
        }
        val reverse = path.coordinates.asReversed().joinToString(";") { coordinate ->
            "${coordinate.lat},${coordinate.lon}"
        }

        return if (forward <= reverse) forward else reverse
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
