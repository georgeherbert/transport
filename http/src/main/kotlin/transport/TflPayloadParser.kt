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
    fun parseLineRoute(body: String, endpoint: String): TransportResult<RailLineRouteRecord>
    fun parsePredictions(body: String, endpoint: String): TransportResult<List<RailPredictionRecord>>
}

data class TflModeStationsPage(
    val stations: List<RailStationRecord>,
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
                    .map(List<List<RailLinePathRecord>>::flatten)
                    .map(::deduplicatePaths)
                    .flatMap { paths ->
                        routeSequence.stopPointSequences
                            .map { stopPointSequence -> parseStopPointSequence(stopPointSequence, endpoint) }
                            .failFast()
                            .map(::deduplicateSequences)
                            .map { sequences ->
                                RailLineRouteRecord(
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

    private inline fun <reified T> decodeList(body: String, endpoint: String): TransportResult<List<T>> =
        try {
            Success(json.decodeFromString<List<T>>(body))
        } catch (exception: SerializationException) {
            Failure(TransportError.UpstreamPayloadFailure(endpoint, exception.message ?: "Invalid JSON payload"))
        }

    private inline fun <reified T> decodeObject(body: String, endpoint: String): TransportResult<T> =
        try {
            Success(json.decodeFromString<T>(body))
        } catch (exception: SerializationException) {
            Failure(TransportError.UpstreamPayloadFailure(endpoint, exception.message ?: "Invalid JSON payload"))
        }

    private fun parseStopPoint(
        stopPoint: TflStopPointJson,
        endpoint: String
    ): TransportResult<List<RailStationRecord>> =
        if (!isSupportedStationStopType(stopPoint.stopType)) {
            Success(emptyList())
        } else {
            val supportedLineIds = stopPoint.lines
                .map(TflIdentifierJson::id)
                .map(::LineId)
                .filter(::isSupportedLineId)

            Success(
                supportedLineIds.map { lineId ->
                    RailStationRecord(
                        StationId(stopPoint.naptanId),
                        StationName(stopPoint.commonName),
                        GeoCoordinate(stopPoint.lat, stopPoint.lon),
                        lineId
                    )
                }
            )
        }

    private fun parseArrival(arrival: TflArrivalJson, endpoint: String): TransportResult<RailPredictionRecord> =
        parseInstant(arrival.timestamp, "timestamp", endpoint)
            .flatMap { observedAt ->
                parseInstant(arrival.expectedArrival, "expectedArrival", endpoint)
                    .map { expectedArrival ->
                        RailPredictionRecord(
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

    private fun parseLineString(lineString: String, endpoint: String): TransportResult<List<RailLinePathRecord>> =
        decodeObject<List<List<List<Double>>>>(lineString, endpoint)
            .flatMap { coordinateGroups ->
                coordinateGroups
                    .map { coordinates -> parseLinePath(coordinates, endpoint) }
                    .failFast()
            }

    private fun parseLinePath(
        coordinates: List<List<Double>>,
        endpoint: String
    ): TransportResult<RailLinePathRecord> =
        coordinates
            .map { coordinate -> parseCoordinate(coordinate, endpoint) }
            .failFast()
            .flatMap { parsedCoordinates ->
                if (parsedCoordinates.isEmpty()) {
                    Failure(TransportError.UpstreamPayloadFailure(endpoint, "TfL returned an empty line path."))
                } else {
                    Success(RailLinePathRecord(parsedCoordinates))
                }
            }

    private fun parseStopPointSequence(
        stopPointSequence: TflRouteStopPointSequenceJson,
        endpoint: String
    ): TransportResult<RailLineSequenceRecord> =
        stopPointSequence.direction
            .takeIf(String::isNotBlank)
            ?.let(::TrainDirection)
            ?.let { direction ->
                Success(
                    RailLineSequenceRecord(
                        direction,
                        stopPointSequence.stopPoint
                            .filter { stopPoint -> isSupportedStationStopType(stopPoint.stopType) }
                            .map(::stationReference)
                    )
                )
            }
            ?: Failure(TransportError.UpstreamPayloadFailure(endpoint, "TfL returned a route sequence without a direction."))

    private fun parseCoordinate(coordinate: List<Double>, endpoint: String): TransportResult<GeoCoordinate> =
        if (coordinate.size < 2) {
            Failure(TransportError.UpstreamPayloadFailure(endpoint, "TfL returned an invalid line coordinate."))
        } else {
            Success(GeoCoordinate(coordinate[1], coordinate[0]))
        }

    private fun deduplicatePaths(paths: List<RailLinePathRecord>) =
        paths.distinctBy(::pathKey)

    private fun deduplicateSequences(sequences: List<RailLineSequenceRecord>) =
        sequences
            .filter { sequence -> sequence.stations.isNotEmpty() }
            .distinctBy(::sequenceKey)

    private fun pathKey(path: RailLinePathRecord): String =
        path.coordinates.joinToString(";") { coordinate ->
            "${coordinate.lat},${coordinate.lon}"
        }.let { forward ->
            path.coordinates.asReversed().joinToString(";") { coordinate ->
                "${coordinate.lat},${coordinate.lon}"
            }.let { reverse ->
                if (forward <= reverse) forward else reverse
            }
        }

    private fun sequenceKey(sequence: RailLineSequenceRecord) =
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
