package transport

import kotlinx.serialization.Serializable

@Serializable
data class TflStopPointJson(
    val naptanId: String,
    val commonName: String,
    val lat: Double,
    val lon: Double,
    val stopType: String,
    val lines: List<TflIdentifierJson>
)

@Serializable
data class TflStopPointsResponseJson(
    val stopPoints: List<TflStopPointJson>,
    val pageSize: Int,
    val total: Int,
    val page: Int
)

@Serializable
data class TflIdentifierJson(
    val id: String
)

@Serializable
data class TflArrivalJson(
    val vehicleId: String? = null,
    val naptanId: String,
    val stationName: String,
    val lineId: String,
    val lineName: String,
    val direction: String? = null,
    val destinationName: String? = null,
    val currentLocation: String? = null,
    val towards: String,
    val expectedArrival: String,
    val modeName: String
)

@Serializable
data class TflRouteSequenceJson(
    val lineId: String,
    val lineName: String,
    val lineStrings: List<String>,
    val stopPointSequences: List<TflRouteStopPointSequenceJson>
)

@Serializable
data class TflRouteStopPointSequenceJson(
    val direction: String,
    val stopPoint: List<TflRouteStopPointJson>
)

@Serializable
data class TflRouteStopPointJson(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val stopType: String
)
