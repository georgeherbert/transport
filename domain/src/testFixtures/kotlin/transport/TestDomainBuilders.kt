package transport

fun testStation(
    id: String,
    name: String,
    lat: Double,
    lon: Double,
    lineIds: Set<String>
) =
    RailStation(
        StationId(id),
        StationName(name),
        GeoCoordinate(lat, lon),
        lineIds.map(::LineId).toSet()
    )

fun testRailNetwork(stations: List<RailStation>) =
    RailNetwork(stations.associateBy(RailStation::id))
