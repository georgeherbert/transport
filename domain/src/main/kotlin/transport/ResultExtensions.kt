package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success

fun <T> Iterable<TransportResult<T>>.failFast(): TransportResult<List<T>> {
    val values = mutableListOf<T>()
    for (result in this) {
        when (result) {
            is Success -> values += result.value
            is Failure -> return Failure(result.reason)
        }
    }
    return Success(values.toList())
}

fun describeTransportError(error: TransportError): String =
    when (error) {
        is TransportError.MetadataUnavailable -> error.message
        is TransportError.SnapshotUnavailable -> error.message
        is TransportError.UpstreamHttpFailure -> "HTTP ${error.statusCode} from ${error.endpoint}"
        is TransportError.UpstreamNetworkFailure -> "${error.endpoint}: ${error.message}"
        is TransportError.UpstreamPayloadFailure -> "${error.endpoint}: ${error.message}"
    }
