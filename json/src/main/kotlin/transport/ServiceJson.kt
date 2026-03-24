package transport

import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorJson(
    val error: String,
    val message: String
)

@Serializable
data class RailMapSnapshotJson(
    val source: String,
    val generatedAt: String,
    val cached: Boolean,
    val cacheAgeSeconds: Long,
    val stationsQueried: Int,
    val stationsFailed: Int,
    val partial: Boolean,
    val trainCount: Int,
    val lines: List<RailLineJson>,
    val stations: List<MapStationJson>,
    val trains: List<RailMapTrainJson>
)

@Serializable
data class RailMapTrainPositionsJson(
    val source: String,
    val generatedAt: String,
    val cached: Boolean,
    val cacheAgeSeconds: Long,
    val stationsQueried: Int,
    val stationsFailed: Int,
    val partial: Boolean,
    val trainCount: Int,
    val trains: List<RailMapTrainJson>
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
    val lineIds: List<String>
)

@Serializable
data class RailMapTrainJson(
    val trainId: String,
    val vehicleId: String?,
    val lineId: String,
    val lineName: String,
    val direction: String?,
    val destinationName: String?,
    val towards: String?,
    val currentLocation: String,
    val coordinate: GeoCoordinateJson?,
    val headingDegrees: Double?,
    val secondsToNextStop: Int?,
    val expectedArrival: String?,
    val observedAt: String?
)

@Serializable
data class GeoCoordinateJson(
    val lat: Double,
    val lon: Double
)
