package transport

import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isGreaterThan

class ClasspathRailLineGeometrySourceTest {
    @Test
    fun `getRailLineGeometry loads the checked in OSM geometry resource`() {
        runBlocking {
            val source: RailLineGeometrySource =
                ClasspathRailLineGeometrySource(transportJson(), "/transport/osm-line-geometry.json")

            val result = source.getRailLineGeometry()

            expectThat(result).isSuccess().get { size }.isEqualTo(supportedRailLineIds.size)
            expectThat(result)
                .isSuccess()
                .get { first { line -> line.lineId == LineId("victoria") }.paths.first().coordinates.size }
                .isGreaterThan(100)
        }
    }

    @Test
    fun `getRailLineGeometry returns failure when the resource is missing`() {
        runBlocking {
            val source: RailLineGeometrySource =
                ClasspathRailLineGeometrySource(transportJson(), "/transport/missing-line-geometry.json")

            val result = source.getRailLineGeometry()

            expectThat(result).isFailure().isA<TransportError.MetadataUnavailable>()
        }
    }
}
