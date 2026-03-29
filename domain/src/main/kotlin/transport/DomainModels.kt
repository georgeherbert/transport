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
    val serviceCount: LiveServiceCount,
    val lines: List<LineId>,
    val services: List<LiveRailService>
)

data class RailMapSnapshot(
    val source: SourceName,
    val generatedAt: Instant,
    val cached: Boolean,
    val cacheAge: Duration,
    val stationsQueried: StationQueryCount,
    val stationsFailed: StationFailureCount,
    val partial: Boolean,
    val serviceCount: LiveServiceCount,
    val lines: List<RailLine>,
    val stations: List<MapStation>,
    val services: List<RailMapService>
)

data class RailMapServicePositions(
    val source: SourceName,
    val generatedAt: Instant,
    val cached: Boolean,
    val cacheAge: Duration,
    val stationsQueried: StationQueryCount,
    val stationsFailed: StationFailureCount,
    val partial: Boolean,
    val serviceCount: LiveServiceCount,
    val stations: List<MapStation>,
    val services: List<RailMapService>
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
    val direction: ServiceDirection,
    val stations: List<StationReference>
)

data class MapStation(
    val id: StationId,
    val name: StationName,
    val coordinate: GeoCoordinate,
    val lineIds: List<LineId>,
    val arrivals: List<StationArrival>
)

data class StationArrival(
    val serviceId: ServiceId,
    val lineId: LineId,
    val lineName: LineName,
    val destinationName: DestinationName?,
    val expectedArrival: Instant
)

data class RailMapService(
    val serviceId: ServiceId,
    val vehicleId: VehicleId,
    val lineId: LineId,
    val lineName: LineName,
    val direction: ServiceDirection?,
    val destinationName: DestinationName?,
    val towards: TowardsDescription?,
    val currentLocation: LocationDescription,
    val nextStop: StationReference?,
    val coordinate: GeoCoordinate?,
    val heading: HeadingDegrees?,
    val expectedArrival: Instant?,
    val observedAt: Instant?,
    val futureArrivals: List<FutureStationArrival>
)

data class LiveRailService(
    val serviceId: ServiceId,
    val vehicleId: VehicleId,
    val lineIds: List<LineId>,
    val lineNames: List<LineName>,
    val direction: ServiceDirection?,
    val destinationName: DestinationName?,
    val towards: TowardsDescription?,
    val currentLocation: LocationDescription,
    val location: LocationEstimate,
    val nextStop: StationReference?,
    val expectedArrival: Instant?,
    val observedAt: Instant?,
    val sourcePredictions: PredictionCount,
    val futureArrivals: List<FutureStationArrival>
)

data class FutureStationArrival(
    val stationId: StationId?,
    val stationName: StationName,
    val expectedArrival: Instant
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
    val vehicleId: VehicleId,
    val stationId: StationId?,
    val stationName: StationName?,
    val lineId: LineId?,
    val lineName: LineName?,
    val direction: ServiceDirection?,
    val destinationName: DestinationName?,
    val observedAt: Instant?,
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
    val direction: ServiceDirection,
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
