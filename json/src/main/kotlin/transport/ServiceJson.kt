package transport

import kotlinx.serialization.Serializable

@Serializable
data class ApiDescriptionJson(
    val service: String,
    val description: String,
    val endpoints: List<String>,
    val notes: List<String>
)

@Serializable
data class HealthJson(
    val status: String,
    val generatedAt: String
)

@Serializable
data class ApiErrorJson(
    val error: String,
    val message: String
)

@Serializable
data class LiveRailSnapshotJson(
    val source: String,
    val generatedAt: String,
    val cached: Boolean,
    val cacheAgeSeconds: Long,
    val stationsQueried: Int,
    val stationsFailed: Int,
    val partial: Boolean,
    val trainCount: Int,
    val lines: List<String>,
    val trains: List<LiveRailTrainJson>
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
data class RailLineMapJson(
    val lines: List<RailLineJson>
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
data class LiveRailTrainJson(
    val trainId: String,
    val vehicleId: String?,
    val lineIds: List<String>,
    val lineNames: List<String>,
    val direction: String?,
    val destinationName: String?,
    val towards: String?,
    val currentLocation: String,
    val location: LocationEstimateJson,
    val nextStop: StationReferenceJson?,
    val secondsToNextStop: Int?,
    val expectedArrival: String?,
    val observedAt: String?,
    val sourcePredictions: Int
)

@Serializable
enum class LocationTypeJson {
    STATION_BOARD,
    UNKNOWN
}

@Serializable
data class LocationEstimateJson(
    val type: LocationTypeJson,
    val description: String,
    val coordinate: GeoCoordinateJson?,
    val station: StationReferenceJson?
)

@Serializable
data class StationReferenceJson(
    val id: String,
    val name: String,
    val coordinate: GeoCoordinateJson
)

@Serializable
data class GeoCoordinateJson(
    val lat: Double,
    val lon: Double
)
