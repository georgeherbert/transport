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
    fun parseModeStationsPage(body: String, endpoint: String): TransportResult<TflModeStationsPage>
    fun parseLineRoute(body: String, endpoint: String): TransportResult<TubeLineRouteRecord>
    fun parsePredictions(body: String, endpoint: String): TransportResult<List<TubePredictionRecord>>
}

data class TflModeStationsPage(
    val stations: List<TubeStationRecord>,
    val page: Int,
    val pageSize: Int,
    val total: Int
)

class TflPayloadParserHttp(
    private val json: Json
) : TflPayloadParser {
    override fun parseModeStationsPage(body: String, endpoint: String) =
        decodeObject<TflStopPointsResponseJson>(body, endpoint)
            .flatMap { stopPointsResponse ->
                stopPointsResponse.stopPoints
                    .map { stopPoint -> parseStopPoint(stopPoint, endpoint) }
                    .failFast()
                    .map { stationRecords ->
                        TflModeStationsPage(
                            stationRecords.flatten(),
                            stopPointsResponse.page,
                            stopPointsResponse.pageSize,
                            stopPointsResponse.total
                        )
                    }
            }

    override fun parseLineRoute(body: String, endpoint: String) =
        decodeObject<TflRouteSequenceJson>(body, endpoint)
            .flatMap { routeSequence ->
                routeSequence.lineStrings
                    .map { lineString -> parseLineString(lineString, endpoint) }
                    .failFast()
                    .map(List<List<TubeLinePathRecord>>::flatten)
                    .map(::deduplicatePaths)
                    .flatMap { paths ->
                        routeSequence.stopPointSequences
                            .map { stopPointSequence -> parseStopPointSequence(stopPointSequence, endpoint) }
                            .failFast()
                            .map(::deduplicateSequences)
                            .map { sequences ->
                                TubeLineRouteRecord(
                                    LineId(routeSequence.lineId),
                                    LineName(routeSequence.lineName),
                                    paths,
                                    sequences
                                )
                            }
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
        endpoint: String
    ): TransportResult<List<TubeStationRecord>> {
        if (!isSupportedStationStopType(stopPoint.stopType)) {
            return Success(emptyList())
        }

        val supportedLineIds = stopPoint.lines
            .map(TflIdentifierJson::id)
            .map(::LineId)
            .filter(::isSupportedLineId)

        return Success(
            supportedLineIds.map { lineId ->
                TubeStationRecord(
                    StationId(stopPoint.naptanId),
                    StationName(stopPoint.commonName),
                    GeoCoordinate(stopPoint.lat, stopPoint.lon),
                    lineId
                )
            }
        )
    }

    private fun parseArrival(arrival: TflArrivalJson, endpoint: String): TransportResult<TubePredictionRecord> =
        parseInstant(arrival.timestamp, "timestamp", endpoint)
            .flatMap { observedAt ->
                parseInstant(arrival.expectedArrival, "expectedArrival", endpoint)
                    .map { expectedArrival ->
                        TubePredictionRecord(
                            arrival.vehicleId.toValue(::VehicleId),
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
                            arrival.currentLocation.toValue(::LocationDescription),
                            arrival.towards.toValue(::TowardsDescription),
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

    private fun parseStopPointSequence(
        stopPointSequence: TflRouteStopPointSequenceJson,
        endpoint: String
    ): TransportResult<TubeLineSequenceRecord> {
        val direction = stopPointSequence.direction
            .takeIf(String::isNotBlank)
            ?.let(::TrainDirection)
            ?: return Failure(TransportError.UpstreamPayloadFailure(endpoint, "TfL returned a route sequence without a direction."))

        return Success(
            TubeLineSequenceRecord(
                direction,
                stopPointSequence.stopPoint
                    .filter { stopPoint -> isSupportedStationStopType(stopPoint.stopType) }
                    .map(::stationReference)
            )
        )
    }

    private fun parseCoordinate(coordinate: List<Double>, endpoint: String): TransportResult<GeoCoordinate> =
        if (coordinate.size < 2) {
            Failure(TransportError.UpstreamPayloadFailure(endpoint, "TfL returned an invalid line coordinate."))
        } else {
            Success(GeoCoordinate(coordinate[1], coordinate[0]))
        }

    private fun deduplicatePaths(paths: List<TubeLinePathRecord>) =
        paths.distinctBy(::pathKey)

    private fun deduplicateSequences(sequences: List<TubeLineSequenceRecord>) =
        sequences
            .filter { sequence -> sequence.stations.isNotEmpty() }
            .distinctBy(::sequenceKey)

    private fun pathKey(path: TubeLinePathRecord): String {
        val forward = path.coordinates.joinToString(";") { coordinate ->
            "${coordinate.lat},${coordinate.lon}"
        }
        val reverse = path.coordinates.asReversed().joinToString(";") { coordinate ->
            "${coordinate.lat},${coordinate.lon}"
        }

        return if (forward <= reverse) forward else reverse
    }

    private fun sequenceKey(sequence: TubeLineSequenceRecord) =
        sequence.direction.value + "|" + sequence.stations.joinToString(";") { station -> station.id.value }

    private fun stationReference(stopPoint: TflRouteStopPointJson) =
        StationReference(
            StationId(stopPoint.id),
            StationName(stopPoint.name),
            GeoCoordinate(stopPoint.lat, stopPoint.lon)
        )

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

    private fun isSupportedStationStopType(stopType: String) =
        stopType == "NaptanMetroStation" || stopType == "NaptanRailStation"

    private fun isSupportedLineId(lineId: LineId) =
        supportedRailLineIds.contains(lineId)
}
