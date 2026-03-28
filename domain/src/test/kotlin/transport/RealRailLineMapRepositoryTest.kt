package transport

import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEqualTo

class RealRailLineMapRepositoryTest {
    private val railData = FakeRailData()
    private val lineGeometrySource = FakeRailLineGeometrySource()
    private val repository = RealRailLineMapRepository(railData, lineGeometrySource)

    @Test
    fun `getRailLineMap combines TfL line sequences with imported line geometry`() {
        runBlocking {
            lineGeometrySource.returns(sampleGeometryRecords())

            val result = repository.getRailLineMap()

            expectThat(result).isSuccess().get { lines }.hasSize(supportedRailLineIds.size)
            expectThat(result).isSuccess().get { lines.first().paths.first().coordinates.first().lat }.isEqualTo(51.0)
        }
    }

    @Test
    fun `getRailLineMap reuses the cached line map`() {
        runBlocking {
            lineGeometrySource.returns(sampleGeometryRecords())

            expectThat(repository.getRailLineMap()).isSuccess()
            expectThat(repository.getRailLineMap()).isSuccess()

            expectThat(railData.lineRouteRequests).hasSize(supportedRailLineIds.size)
            expectThat(lineGeometrySource.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `getRailLineMap returns failure when a route call fails`() {
        runBlocking {
            val failingLineId = supportedRailLineIds.first()
            railData.failsLineRoute(
                failingLineId,
                TransportError.UpstreamNetworkFailure("/Line/${failingLineId.value}/Route/Sequence/all", "boom")
            )
            lineGeometrySource.returns(sampleGeometryRecords())

            val result = repository.getRailLineMap()

            expectThat(result).isFailure().isA<TransportError.UpstreamNetworkFailure>()
        }
    }

    @Test
    fun `getRailLineMap returns failure when imported geometry is missing for a supported line`() {
        runBlocking {
            lineGeometrySource.returns(sampleGeometryRecords().drop(1))

            val result = repository.getRailLineMap()

            expectThat(result).isFailure().isA<TransportError.MetadataUnavailable>()
        }
    }

    @Test
    fun `getRailLineMap returns geometry source failures unchanged`() {
        runBlocking {
            lineGeometrySource.failsWith(TransportError.MetadataUnavailable("missing geometry"))

            val result = repository.getRailLineMap()

            expectThat(result).isFailure().isA<TransportError.MetadataUnavailable>()
        }
    }

    private fun sampleGeometryRecords() =
        supportedRailLineIds.map { lineId ->
            RailLineGeometryRecord(
                lineId,
                listOf(
                    RailLinePathRecord(
                        listOf(
                            GeoCoordinate(51.0, -0.1),
                            GeoCoordinate(51.1, -0.2)
                        )
                    )
                )
            )
        }
}
