package transport

import java.time.Duration
import java.time.Instant

val transportSourceName = SourceName("TfL Unified API")

data class LiveRailSnapshot(
    val source: SourceName,
    val generatedAt: Instant,
    val cached: Boolean,
    val cacheAge: Duration,
    val stationsQueried: StationQueryCount,
    val stationsFailed: StationFailureCount,
    val partial: Boolean,
    val trainCount: LiveTrainCount,
    val lines: List<LineId>,
    val trains: List<LiveRailTrain>
)

data class RailMapSnapshot(
    val source: SourceName,
    val generatedAt: Instant,
    val cached: Boolean,
    val cacheAge: Duration,
    val stationsQueried: StationQueryCount,
    val stationsFailed: StationFailureCount,
    val partial: Boolean,
    val trainCount: LiveTrainCount,
    val lines: List<RailLine>,
    val stations: List<MapStation>,
    val trains: List<RailMapTrain>
)

data class RailMapTrainPositions(
    val source: SourceName,
    val generatedAt: Instant,
    val cached: Boolean,
    val cacheAge: Duration,
    val stationsQueried: StationQueryCount,
    val stationsFailed: StationFailureCount,
    val partial: Boolean,
    val trainCount: LiveTrainCount,
    val trains: List<RailMapTrain>
)

data class RailLineMap(
    val lines: List<RailLine>
)

data class RailLine(
    val id: LineId,
    val name: LineName,
    val paths: List<RailLinePath>,
    val sequences: List<RailLineSequence>
)

data class RailLinePath(
    val coordinates: List<GeoCoordinate>
)

data class RailLineSequence(
    val direction: TrainDirection,
    val stations: List<StationReference>
)

data class MapStation(
    val id: StationId,
    val name: StationName,
    val coordinate: GeoCoordinate,
    val lineIds: List<LineId>
)

data class RailMapTrain(
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

data class LiveRailTrain(
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

data class RailStationRecord(
    val stationId: StationId,
    val name: StationName,
    val coordinate: GeoCoordinate,
    val lineId: LineId
)

data class RailPredictionRecord(
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

data class RailLineRouteRecord(
    val lineId: LineId,
    val lineName: LineName,
    val paths: List<RailLinePathRecord>,
    val sequences: List<RailLineSequenceRecord>
)

data class RailLineGeometryRecord(
    val lineId: LineId,
    val paths: List<RailLinePathRecord>
)

data class RailLinePathRecord(
    val coordinates: List<GeoCoordinate>
)

data class RailLineSequenceRecord(
    val direction: TrainDirection,
    val stations: List<StationReference>
)

data class RailStation(
    val id: StationId,
    val name: StationName,
    val coordinate: GeoCoordinate,
    val lineIds: Set<LineId>
)

data class RailNetwork(
    val stationsById: Map<StationId, RailStation>,
    val aliases: Map<String, List<RailStation>>
)

fun RailStation.toReference() =
    StationReference(id, name, coordinate)
