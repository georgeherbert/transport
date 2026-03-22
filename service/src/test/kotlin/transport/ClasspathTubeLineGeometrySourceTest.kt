package transport

import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isGreaterThan

class ClasspathTubeLineGeometrySourceTest {
    @Test
    fun `getTubeLineGeometry loads the checked in OSM geometry resource`() {
        runBlocking {
            val source: TubeLineGeometrySource =
                ClasspathTubeLineGeometrySource(transportJson(), "/transport/osm-line-geometry.json")

            val result = source.getTubeLineGeometry()

            expectThat(result).isSuccess().get { size }.isEqualTo(supportedRailLineIds.size)
            expectThat(result)
                .isSuccess()
                .get { first { line -> line.lineId == LineId("victoria") }.paths.first().coordinates.size }
                .isGreaterThan(100)
        }
    }

    @Test
    fun `getTubeLineGeometry returns failure when the resource is missing`() {
        runBlocking {
            val source: TubeLineGeometrySource =
                ClasspathTubeLineGeometrySource(transportJson(), "/transport/missing-line-geometry.json")

            val result = source.getTubeLineGeometry()

            expectThat(result).isFailure().isA<TransportError.MetadataUnavailable>()
        }
    }
}
