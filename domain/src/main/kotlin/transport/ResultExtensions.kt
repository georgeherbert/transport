package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.flatMap

fun <T> Iterable<TransportResult<T>>.failFast(): TransportResult<List<T>> =
    fold<TransportResult<T>, TransportResult<List<T>>>(Success(emptyList())) { accumulatedResult, nextResult ->
        accumulatedResult.flatMap { accumulatedValues ->
            when (nextResult) {
                is Success -> Success(accumulatedValues + nextResult.value)
                is Failure -> Failure(nextResult.reason)
            }
        }
    }

fun describeTransportError(error: TransportError): String =
    when (error) {
        is TransportError.MetadataUnavailable -> error.message
        is TransportError.SnapshotUnavailable -> error.message
        is TransportError.UpstreamHttpFailure -> "HTTP ${error.statusCode} from ${error.endpoint}"
        is TransportError.UpstreamNetworkFailure -> "${error.endpoint}: ${error.message}"
        is TransportError.UpstreamPayloadFailure -> "${error.endpoint}: ${error.message}"
    }
