package transport

import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorJson(
    val error: String,
    val message: String
)

@Serializable
data class RailMapSnapshotJson(
    val generatedAt: String,
    val stationsFailed: Int,
    val partial: Boolean,
    val serviceCount: Int,
    val lines: List<RailLineJson>,
    val stations: List<MapStationJson>,
    val services: List<RailMapServiceJson>
)

@Serializable
data class RailMapServicePositionsJson(
    val generatedAt: String,
    val stationsFailed: Int,
    val partial: Boolean,
    val serviceCount: Int,
    val stations: List<MapStationJson>,
    val services: List<RailMapServiceJson>
)

@Serializable
data class RailLineJson(
    val id: String,
    val name: String,
    val paths: List<RailLinePathJson>
)

@Serializable
data class RailLinePathJson(
    val coordinates: List<GeoCoordinateJson>
)

@Serializable
data class MapStationJson(
    val id: String,
    val name: String,
    val coordinate: GeoCoordinateJson,
    val lineIds: List<String>,
    val arrivals: List<StationArrivalJson>
)

@Serializable
data class StationArrivalJson(
    val serviceId: String,
    val lineId: String,
    val destinationName: String?,
    val expectedArrival: String
)

@Serializable
data class RailMapServiceJson(
    val serviceId: String,
    val lineId: String,
    val lineName: String,
    val destinationName: String?,
    val towards: String?,
    val currentLocation: String,
    val coordinate: GeoCoordinateJson?,
    val headingDegrees: Double?,
    val futureArrivals: List<FutureStationArrivalJson>
)

@Serializable
data class FutureStationArrivalJson(
    val stationId: String?,
    val stationName: String,
    val expectedArrival: String
)

@Serializable
data class GeoCoordinateJson(
    val lat: Double,
    val lon: Double
)
