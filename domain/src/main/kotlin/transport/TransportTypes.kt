package transport

import dev.forkhandles.result4k.Result4k

typealias TransportResult<T> = Result4k<T, TransportError>

sealed interface TransportError {
    data class MetadataUnavailable(val message: String) : TransportError
    data class SnapshotUnavailable(val message: String) : TransportError
    data class UpstreamHttpFailure(val endpoint: String, val statusCode: Int, val responseBody: String) : TransportError
    data class UpstreamNetworkFailure(val endpoint: String, val message: String) : TransportError
    data class UpstreamPayloadFailure(val endpoint: String, val message: String) : TransportError
}

@JvmInline
value class SourceName(val value: String)

@JvmInline
value class LineId(val value: String)

@JvmInline
value class LineName(val value: String)

@JvmInline
value class StationId(val value: String)

@JvmInline
value class StationName(val value: String)

@JvmInline
value class TrainId(val value: String)

@JvmInline
value class VehicleId(val value: String)

@JvmInline
value class TrainDirection(val value: String)

@JvmInline
value class DestinationName(val value: String)

@JvmInline
value class TowardsDescription(val value: String)

@JvmInline
value class LocationDescription(val value: String)

@JvmInline
value class TransportModeName(val value: String)

@JvmInline
value class StationQueryCount(val value: Int)

@JvmInline
value class StationFailureCount(val value: Int)

@JvmInline
value class LiveTrainCount(val value: Int)

@JvmInline
value class PredictionCount(val value: Int)

@JvmInline
value class HeadingDegrees(val value: Double)

data class SupportedLine(
    val id: LineId,
    val mode: TransportModeName
)

val tubeMode = TransportModeName("tube")

val supportedRailLines = listOf(
    SupportedLine(LineId("bakerloo"), tubeMode),
    SupportedLine(LineId("central"), tubeMode),
    SupportedLine(LineId("circle"), tubeMode),
    SupportedLine(LineId("district"), tubeMode),
    SupportedLine(LineId("hammersmith-city"), tubeMode),
    SupportedLine(LineId("jubilee"), tubeMode),
    SupportedLine(LineId("metropolitan"), tubeMode),
    SupportedLine(LineId("northern"), tubeMode),
    SupportedLine(LineId("piccadilly"), tubeMode),
    SupportedLine(LineId("victoria"), tubeMode),
    SupportedLine(LineId("waterloo-city"), tubeMode),
    SupportedLine(LineId("elizabeth"), TransportModeName("elizabeth-line")),
    SupportedLine(LineId("liberty"), TransportModeName("overground")),
    SupportedLine(LineId("lioness"), TransportModeName("overground")),
    SupportedLine(LineId("mildmay"), TransportModeName("overground")),
    SupportedLine(LineId("suffragette"), TransportModeName("overground")),
    SupportedLine(LineId("weaver"), TransportModeName("overground")),
    SupportedLine(LineId("windrush"), TransportModeName("overground")),
    SupportedLine(LineId("tram"), TransportModeName("tram"))
)

val supportedRailLineIds = supportedRailLines.map(SupportedLine::id)
val supportedTubeLineIds = supportedRailLines
    .filter { line -> line.mode == tubeMode }
    .map(SupportedLine::id)
val supportedTubeLineIdSet = supportedTubeLineIds.toSet()
val supportedMapStationLineIds = supportedRailLineIds
val supportedMapStationLineIdSet = supportedMapStationLineIds.toSet()

val supportedRailModes = supportedRailLines
    .map(SupportedLine::mode)
    .distinctBy(TransportModeName::value)
