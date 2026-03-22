package transport

import io.ktor.client.HttpClient
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.time.Clock

fun main() {
    val transportServiceConfig = loadTransportServiceConfig(System.getenv())
    val json = transportJson()
    val serviceResponseMapper = ServiceResponseMapperHttp()
    val httpClient = createTflHttpClient(transportServiceConfig.requestTimeout)
    val services = createTransportServices(transportServiceConfig, json, httpClient)

    try {
        embeddedServer(
            factory = Netty,
            port = transportServiceConfig.port,
            host = transportServiceConfig.host,
            module = {
                transportModule(
                    services.tubeSnapshotService,
                    services.tubeLineMapService,
                    services.tubeMapService,
                    serviceResponseMapper,
                    json
                )
            }
        ).start(wait = true)
    } finally {
        httpClient.close()
    }
}

fun createTubeSnapshotService(
    transportServiceConfig: TransportServiceConfig,
    json: kotlinx.serialization.json.Json
): TubeSnapshotService =
    createTransportServices(
        transportServiceConfig,
        json,
        createTflHttpClient(transportServiceConfig.requestTimeout)
    ).tubeSnapshotService

fun createTubeLineMapService(
    transportServiceConfig: TransportServiceConfig,
    json: kotlinx.serialization.json.Json
): TubeLineMapService =
    createTransportServices(
        transportServiceConfig,
        json,
        createTflHttpClient(transportServiceConfig.requestTimeout)
    ).tubeLineMapService

fun createTubeMapService(
    transportServiceConfig: TransportServiceConfig,
    json: kotlinx.serialization.json.Json
): TubeMapService =
    createTransportServices(
        transportServiceConfig,
        json,
        createTflHttpClient(transportServiceConfig.requestTimeout)
    ).tubeMapService

private fun createTransportServices(
    transportServiceConfig: TransportServiceConfig,
    json: kotlinx.serialization.json.Json,
    httpClient: HttpClient
): TransportServices {
    val tubeData = TubeDataHttp(
        transportServiceConfig.toTflHttpClientConfig(),
        httpClient,
        TflPayloadParserHttp(json)
    )
    val tubeMetadataRepository = RealTubeMetadataRepository(tubeData)
    val tubeLineMapRepository = RealTubeLineMapRepository(tubeData)
    val tubeLocationEstimator = RealTubeLocationEstimator()
    val tubeSnapshotAssembler = RealTubeSnapshotAssembler(tubeLocationEstimator)
    val tubePathSmoother = RealTubePathSmoother(8)
    val tubeMapProjector = RealTubeMapProjector(tubePathSmoother)
    val tubeSnapshotService = RealTubeSnapshotService(
        tubeData,
        tubeMetadataRepository,
        tubeSnapshotAssembler,
        Clock.systemUTC(),
        transportServiceConfig.cacheTtl
    )
    val tubeLineMapService = RealTubeLineMapService(tubeLineMapRepository)

    return TransportServices(
        tubeSnapshotService,
        tubeLineMapService,
        RealTubeMapService(
            tubeSnapshotService,
            tubeLineMapService,
            tubeMapProjector
        )
    )
}

private data class TransportServices(
    val tubeSnapshotService: TubeSnapshotService,
    val tubeLineMapService: TubeLineMapService,
    val tubeMapService: TubeMapService
)
