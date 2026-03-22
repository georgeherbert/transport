package transport

import kotlinx.serialization.Serializable

@Serializable
data class OsmLineGeometryCollectionJson(
    val lines: List<OsmLineGeometryJson>
)

@Serializable
data class OsmLineGeometryJson(
    val lineId: String,
    val paths: List<OsmLinePathJson>
)

@Serializable
data class OsmLinePathJson(
    val coordinates: List<GeoCoordinateJson>
)
