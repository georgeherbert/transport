package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.flatMap
import dev.forkhandles.result4k.map
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class ClasspathTubeLineGeometrySource(
    private val json: Json,
    private val resourcePath: String
) : TubeLineGeometrySource {
    override suspend fun getTubeLineGeometry() =
        readResource()
            .flatMap(::decodeGeometry)

    private fun readResource(): TransportResult<String> =
        javaClass.getResourceAsStream(resourcePath)
            ?.let { inputStream ->
                try {
                    Success(inputStream.bufferedReader().use { reader -> reader.readText() })
                } catch (exception: IOException) {
                    Failure(
                        TransportError.MetadataUnavailable(
                            "Unable to read imported line geometry resource '$resourcePath'."
                        )
                    )
                }
            }
            ?: Failure(
                TransportError.MetadataUnavailable(
                    "Unable to find imported line geometry resource '$resourcePath'."
                )
            )

    private fun decodeGeometry(payload: String): TransportResult<List<TubeLineGeometryRecord>> =
        try {
            Success(json.decodeFromString<OsmLineGeometryCollectionJson>(payload))
                .map(OsmLineGeometryCollectionJson::lines)
                .map { lines ->
                    lines.map(::toLineGeometryRecord)
                }
        } catch (exception: SerializationException) {
            Failure(
                TransportError.MetadataUnavailable(
                    "Imported line geometry resource '$resourcePath' is invalid JSON."
                )
            )
        }

    private fun toLineGeometryRecord(line: OsmLineGeometryJson) =
        TubeLineGeometryRecord(
            LineId(line.lineId),
            line.paths.map { path ->
                TubeLinePathRecord(
                    path.coordinates.map { coordinate ->
                        GeoCoordinate(coordinate.lat, coordinate.lon)
                    }
                )
            }
        )
}
