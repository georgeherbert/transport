package transport

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

fun squaredDistance(left: GeoCoordinate, right: GeoCoordinate): Double =
    (left.lat - right.lat).pow(2) + (left.lon - right.lon).pow(2)

fun distance(left: GeoCoordinate, right: GeoCoordinate): Double =
    sqrt(squaredDistance(left, right))

fun bearingBetween(startCoordinate: GeoCoordinate, endCoordinate: GeoCoordinate): HeadingDegrees? =
    projectToMercator(startCoordinate).let { startProjected ->
        projectToMercator(endCoordinate).let { endProjected ->
            val xDelta = endProjected.x - startProjected.x
            val yDelta = endProjected.y - startProjected.y

            if (abs(xDelta) < 0.0000001 && abs(yDelta) < 0.0000001) {
                null
            } else {
                val rawDegrees = Math.toDegrees(atan2(xDelta, yDelta))
                val normalizedDegrees = (rawDegrees + 360.0) % 360.0
                HeadingDegrees(normalizedDegrees)
            }
        }
    }

fun interpolate(startCoordinate: GeoCoordinate, endCoordinate: GeoCoordinate, fraction: Double) =
    GeoCoordinate(
        startCoordinate.lat + ((endCoordinate.lat - startCoordinate.lat) * fraction),
        startCoordinate.lon + ((endCoordinate.lon - startCoordinate.lon) * fraction)
    )

private fun projectToMercator(coordinate: GeoCoordinate) =
    MapPlaneCoordinate(
        Math.toRadians(coordinate.lon),
        ln(tan((Math.PI / 4.0) + (Math.toRadians(coordinate.lat) / 2.0)))
    )

data class MapPlaneCoordinate(
    val x: Double,
    val y: Double
)
