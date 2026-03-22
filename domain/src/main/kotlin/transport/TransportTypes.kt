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

val tubeLineIds = listOf(
    LineId("bakerloo"),
    LineId("central"),
    LineId("circle"),
    LineId("district"),
    LineId("hammersmith-city"),
    LineId("jubilee"),
    LineId("metropolitan"),
    LineId("northern"),
    LineId("piccadilly"),
    LineId("victoria"),
    LineId("waterloo-city")
)
