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

val supportedRailLines = listOf(
    SupportedLine(LineId("bakerloo"), TransportModeName("tube")),
    SupportedLine(LineId("central"), TransportModeName("tube")),
    SupportedLine(LineId("circle"), TransportModeName("tube")),
    SupportedLine(LineId("district"), TransportModeName("tube")),
    SupportedLine(LineId("hammersmith-city"), TransportModeName("tube")),
    SupportedLine(LineId("jubilee"), TransportModeName("tube")),
    SupportedLine(LineId("metropolitan"), TransportModeName("tube")),
    SupportedLine(LineId("northern"), TransportModeName("tube")),
    SupportedLine(LineId("piccadilly"), TransportModeName("tube")),
    SupportedLine(LineId("victoria"), TransportModeName("tube")),
    SupportedLine(LineId("waterloo-city"), TransportModeName("tube")),
    SupportedLine(LineId("dlr"), TransportModeName("dlr")),
    SupportedLine(LineId("elizabeth"), TransportModeName("elizabeth-line")),
    SupportedLine(LineId("liberty"), TransportModeName("overground")),
    SupportedLine(LineId("lioness"), TransportModeName("overground")),
    SupportedLine(LineId("mildmay"), TransportModeName("overground")),
    SupportedLine(LineId("suffragette"), TransportModeName("overground")),
    SupportedLine(LineId("weaver"), TransportModeName("overground")),
    SupportedLine(LineId("windrush"), TransportModeName("overground"))
)

val supportedRailLineIds = supportedRailLines.map(SupportedLine::id)

val supportedRailModes = supportedRailLines
    .map(SupportedLine::mode)
    .distinctBy(TransportModeName::value)
