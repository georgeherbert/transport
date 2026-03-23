package transport

import java.time.Duration
import java.time.Instant

val transportSourceName = SourceName("TfL Unified API")

data class LiveTubeSnapshot(
    val source: SourceName,
    val generatedAt: Instant,
    val cached: Boolean,
    val cacheAge: Duration,
    val stationsQueried: StationQueryCount,
    val stationsFailed: StationFailureCount,
    val partial: Boolean,
    val trainCount: LiveTrainCount,
    val lines: List<LineId>,
    val trains: List<LiveTubeTrain>
)

data class TubeMapSnapshot(
    val source: SourceName,
    val generatedAt: Instant,
    val cached: Boolean,
    val cacheAge: Duration,
    val stationsQueried: StationQueryCount,
    val stationsFailed: StationFailureCount,
    val partial: Boolean,
    val trainCount: LiveTrainCount,
    val lines: List<TubeLine>,
    val stations: List<MapStation>,
    val trains: List<TubeMapTrain>
)

data class TubeMapTrainPositions(
    val source: SourceName,
    val generatedAt: Instant,
    val cached: Boolean,
    val cacheAge: Duration,
    val stationsQueried: StationQueryCount,
    val stationsFailed: StationFailureCount,
    val partial: Boolean,
    val trainCount: LiveTrainCount,
    val trains: List<TubeMapTrain>
)

data class TubeLineMap(
    val lines: List<TubeLine>
)

data class TubeLine(
    val id: LineId,
    val name: LineName,
    val paths: List<TubeLinePath>,
    val sequences: List<TubeLineSequence>
)

data class TubeLinePath(
    val coordinates: List<GeoCoordinate>
)

data class TubeLineSequence(
    val direction: TrainDirection,
    val stations: List<StationReference>
)

data class MapStation(
    val id: StationId,
    val name: StationName,
    val coordinate: GeoCoordinate,
    val lineIds: List<LineId>
)

data class TubeMapTrain(
    val trainId: TrainId,
    val vehicleId: VehicleId?,
    val lineId: LineId,
    val lineName: LineName,
    val direction: TrainDirection?,
    val destinationName: DestinationName?,
    val towards: TowardsDescription?,
    val currentLocation: LocationDescription,
    val nextStop: StationReference?,
    val coordinate: GeoCoordinate?,
    val heading: HeadingDegrees?,
    val secondsToNextStop: Duration?,
    val expectedArrival: Instant?,
    val observedAt: Instant?
)

data class LiveTubeTrain(
    val trainId: TrainId,
    val vehicleId: VehicleId?,
    val lineIds: List<LineId>,
    val lineNames: List<LineName>,
    val direction: TrainDirection?,
    val destinationName: DestinationName?,
    val towards: TowardsDescription?,
    val currentLocation: LocationDescription,
    val location: LocationEstimate,
    val nextStop: StationReference?,
    val secondsToNextStop: Duration?,
    val expectedArrival: Instant?,
    val observedAt: Instant?,
    val sourcePredictions: PredictionCount
)

enum class LocationType {
    STATION_BOARD,
    UNKNOWN
}

data class LocationEstimate(
    val type: LocationType,
    val description: LocationDescription,
    val coordinate: GeoCoordinate?,
    val station: StationReference?
)

data class StationReference(
    val id: StationId,
    val name: StationName,
    val coordinate: GeoCoordinate
)

data class GeoCoordinate(
    val lat: Double,
    val lon: Double
)

data class TubeStationRecord(
    val stationId: StationId,
    val name: StationName,
    val coordinate: GeoCoordinate,
    val lineId: LineId
)

data class TubePredictionRecord(
    val vehicleId: VehicleId?,
    val stationId: StationId?,
    val stationName: StationName?,
    val lineId: LineId?,
    val lineName: LineName?,
    val direction: TrainDirection?,
    val destinationName: DestinationName?,
    val observedAt: Instant?,
    val secondsToNextStop: Duration?,
    val currentLocation: LocationDescription?,
    val towards: TowardsDescription?,
    val expectedArrival: Instant?,
    val modeName: TransportModeName?
)

data class TubeLineRouteRecord(
    val lineId: LineId,
    val lineName: LineName,
    val paths: List<TubeLinePathRecord>,
    val sequences: List<TubeLineSequenceRecord>
)

data class TubeLineGeometryRecord(
    val lineId: LineId,
    val paths: List<TubeLinePathRecord>
)

data class TubeLinePathRecord(
    val coordinates: List<GeoCoordinate>
)

data class TubeLineSequenceRecord(
    val direction: TrainDirection,
    val stations: List<StationReference>
)

data class TubeStation(
    val id: StationId,
    val name: StationName,
    val coordinate: GeoCoordinate,
    val lineIds: Set<LineId>
)

data class TubeNetwork(
    val stationsById: Map<StationId, TubeStation>,
    val aliases: Map<String, List<TubeStation>>
)

fun TubeStation.toReference() =
    StationReference(id, name, coordinate)
