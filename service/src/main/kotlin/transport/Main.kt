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
    val json = transportJson()
    val serviceResponseMapper = ServiceResponseMapperHttp()
    val httpClient = createTflHttpClient(transportServiceConfig.requestTimeout)
    val railLinePathProjectionFactory: RailLinePathProjectionFactory = RealRailLinePathProjectionFactory()
    val railLineProjectionFactory: RailLineProjectionFactory =
        RealRailLineProjectionFactory(railLinePathProjectionFactory)
    val services = createTransportServices(transportServiceConfig, json, httpClient)
    val feedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val railMapMotionEngine: RailMapMotionEngine = RealRailMapMotionEngine(railLineProjectionFactory)
    val railMapFeedService: RailMapFeedService =
        RealRailMapFeedService(
            services.railMapService,
            railMapMotionEngine,
            Clock.systemUTC(),
            transportServiceConfig.railMapPollInterval,
            feedScope
        )

    try {
        runBlocking {
            railMapFeedService.start()
        }

        embeddedServer(
            factory = Netty,
            port = transportServiceConfig.port,
            host = transportServiceConfig.host,
            module = {
                transportModule(
                    railMapFeedService,
                    serviceResponseMapper,
                    json
                )
            }
        ).start(true)
    } finally {
        feedScope.cancel()
        httpClient.close()
    }
}

fun createRailSnapshotService(
    transportServiceConfig: TransportServiceConfig,
    json: kotlinx.serialization.json.Json
): RailSnapshotService =
    createTransportServices(
        transportServiceConfig,
        json,
        createTflHttpClient(transportServiceConfig.requestTimeout)
    ).railSnapshotService

private fun createTransportServices(
    transportServiceConfig: TransportServiceConfig,
    json: kotlinx.serialization.json.Json,
    httpClient: HttpClient
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
        val railPathSmoother: RailPathSmoother = RealIdentityRailPathSmoother()
        val railLinePathProjectionFactory: RailLinePathProjectionFactory = RealRailLinePathProjectionFactory()
        val railLineProjectionFactory: RailLineProjectionFactory =
            RealRailLineProjectionFactory(railLinePathProjectionFactory)
        val railMapProjector = RealRailMapProjector(railPathSmoother, railLineProjectionFactory)
        val railSnapshotService = RealRailSnapshotService(
            railData,
            railMetadataRepository,
            railSnapshotAssembler,
            Clock.systemUTC(),
            transportServiceConfig.railSnapshotCacheTtl
        )
        val railLineMapService = RealRailLineMapService(railLineMapRepository)

        TransportServices(
            railSnapshotService,
            railLineMapService,
            RealRailMapService(
                railSnapshotService,
                railLineMapService,
                railMapProjector
            )
        )
    }

private data class TransportServices(
    val railSnapshotService: RailSnapshotService,
    val railLineMapService: RailLineMapService,
    val railMapService: RailMapService
)
