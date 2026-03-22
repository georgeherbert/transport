package transport

fun testStation(
    id: String,
    name: String,
    lat: Double,
    lon: Double,
    lineIds: Set<String>
) =
    TubeStation(
        StationId(id),
        StationName(name),
        GeoCoordinate(lat, lon),
        lineIds.map(::LineId).toSet()
    )

fun testTubeNetwork(stations: List<TubeStation>) =
    TubeNetwork(stations.associateBy(TubeStation::id), buildAliasIndex(stations))
