package transport

import kotlinx.serialization.Serializable

@Serializable
data class TflStopPointJson(
    val id: String,
    val naptanId: String,
    val commonName: String,
    val lat: Double,
    val lon: Double,
    val stopType: String
)

@Serializable
data class TflArrivalJson(
    val id: String,
    val vehicleId: String,
    val naptanId: String,
    val stationName: String,
    val lineId: String,
    val lineName: String,
    val platformName: String,
    val direction: String? = null,
    val destinationNaptanId: String? = null,
    val destinationName: String? = null,
    val timestamp: String,
    val timeToStation: Int? = null,
    val currentLocation: String,
    val towards: String,
    val expectedArrival: String,
    val timeToLive: String,
    val modeName: String
)
