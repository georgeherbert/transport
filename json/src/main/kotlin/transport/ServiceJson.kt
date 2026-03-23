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
data class LiveTubeSnapshotJson(
    val source: String,
    val generatedAt: String,
    val cached: Boolean,
    val cacheAgeSeconds: Long,
    val stationsQueried: Int,
    val stationsFailed: Int,
    val partial: Boolean,
    val trainCount: Int,
    val lines: List<String>,
    val trains: List<LiveTubeTrainJson>
)

@Serializable
data class TubeMapSnapshotJson(
    val source: String,
    val generatedAt: String,
    val cached: Boolean,
    val cacheAgeSeconds: Long,
    val stationsQueried: Int,
    val stationsFailed: Int,
    val partial: Boolean,
    val trainCount: Int,
    val lines: List<TubeLineJson>,
    val tubeStations: List<TubeMapStationJson>,
    val trains: List<TubeMapTrainJson>
)

@Serializable
data class TubeLineMapJson(
    val lines: List<TubeLineJson>
)

@Serializable
data class TubeLineJson(
    val id: String,
    val name: String,
    val paths: List<TubeLinePathJson>
)

@Serializable
data class TubeLinePathJson(
    val coordinates: List<GeoCoordinateJson>
)

@Serializable
data class TubeMapStationJson(
    val id: String,
    val name: String,
    val coordinate: GeoCoordinateJson,
    val lineIds: List<String>
)

@Serializable
data class TubeMapTrainJson(
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
data class LiveTubeTrainJson(
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
    AT_STATION,
    APPROACHING_STATION,
    BETWEEN_STATIONS,
    DEPARTED_STATION,
    NEAR_STATION,
    STATION_BOARD,
    UNKNOWN
}

@Serializable
data class LocationEstimateJson(
    val type: LocationTypeJson,
    val description: String,
    val coordinate: GeoCoordinateJson?,
    val station: StationReferenceJson?,
    val fromStation: StationReferenceJson?,
    val toStation: StationReferenceJson?
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
