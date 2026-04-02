package transport

import io.ktor.client.HttpClient
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

fun main() {
    val transportServiceConfig = loadTransportServiceConfig(System.getenv())
    val transportRuntime = createTransportRuntime(transportServiceConfig)

    try {
        runBlocking {
            transportRuntime.railMapFeedService.start()
        }

        embeddedServer(
            factory = Netty,
            port = transportServiceConfig.port,
            host = transportServiceConfig.host,
            module = {
                transportModule(
                    transportRuntime.railMapFeedService,
                    transportRuntime.serviceResponseMapper,
                    transportRuntime.transportJson
                )
            }
        ).start(true)
    } finally {
        transportRuntime.close()
    }
}

private fun createTransportRuntime(transportServiceConfig: TransportServiceConfig): TransportRuntime {
    val transportJson = transportJson()
    val serviceResponseMapper: ServiceResponseMapper = ServiceResponseMapperHttp()
    val httpClient = createTflHttpClient(transportServiceConfig.requestTimeout)
    val clock = Clock.systemUTC()
    val services = createTransportServices(transportServiceConfig, transportJson, httpClient, clock)
    val feedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    return TransportRuntime(
        httpClient,
        feedScope,
        RealRailMapFeedService(
            services.railMapQuery,
            createRailMapMotionEngine(),
            clock,
            transportServiceConfig.railMapPollInterval,
            feedScope
        ),
        serviceResponseMapper,
        transportJson
    )
}

private fun createTransportServices(
    transportServiceConfig: TransportServiceConfig,
    json: kotlinx.serialization.json.Json,
    httpClient: HttpClient,
    clock: Clock
): TransportServices =
    RailDataHttp(
        transportServiceConfig.toTflHttpClientConfig(),
        httpClient,
        TflPayloadParserHttp(json)
    ).let { railData ->
        val railLineGeometrySource: RailLineGeometrySource =
            ClasspathRailLineGeometrySource(json, "/transport/osm-line-geometry.json")
        val railMetadataRepository = RealRailMetadataRepository(railData)
        val railLineMapRepository = RealRailLineMapRepository(railData, railLineGeometrySource)
        val railLocationEstimator = RealRailLocationEstimator()
        val railSnapshotAssembler = RealRailSnapshotAssembler(railLocationEstimator)
        val railMapProjector = createRailMapProjector()
        val railSnapshotService = RealRailSnapshotService(
            railData,
            railMetadataRepository,
            railSnapshotAssembler,
            clock
        )
        val railLineMapService = RealRailLineMapService(railLineMapRepository)

        TransportServices(
            railSnapshotService,
            railLineMapService,
            RealRailMapQuery(
                railSnapshotService,
                railLineMapService,
                railMapProjector
            )
        )
    }

private fun createRailLineProjectionFactory(): RailLineProjectionFactory =
    RealRailLineProjectionFactory(RealRailLinePathProjectionFactory())

private fun createRailMapMotionEngine(): RailMapMotionEngine =
    RealRailMapMotionEngine(createRailLineProjectionFactory())

private fun createRailMapProjector(): RailMapProjector =
    RealRailMapProjector(RealIdentityRailPathSmoother(), createRailLineProjectionFactory())

private data class TransportServices(
    val railSnapshotService: RailSnapshotService,
    val railLineMapService: RailLineMapService,
    val railMapQuery: RailMapQuery
)

private data class TransportRuntime(
    val httpClient: HttpClient,
    val feedScope: CoroutineScope,
    val railMapFeedService: RailMapFeedService,
    val serviceResponseMapper: ServiceResponseMapper,
    val transportJson: kotlinx.serialization.json.Json
) {
    fun close() {
        feedScope.cancel()
        httpClient.close()
    }
}
