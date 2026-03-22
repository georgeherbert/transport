package transport

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.http.HttpClient
import java.time.Clock

fun main() {
    val transportServiceConfig = loadTransportServiceConfig(System.getenv())
    val json = transportJson()
    val serviceResponseMapper = ServiceResponseMapperHttp()
    val tubeSnapshotService = createTubeSnapshotService(transportServiceConfig, json)

    embeddedServer(
        factory = Netty,
        port = transportServiceConfig.port,
        host = transportServiceConfig.host,
        module = {
            transportModule(tubeSnapshotService, serviceResponseMapper, json)
        }
    ).start(wait = true)
}

fun createTubeSnapshotService(
    transportServiceConfig: TransportServiceConfig,
    json: kotlinx.serialization.json.Json
): TubeSnapshotService {
    val httpClient = HttpClient.newBuilder()
        .connectTimeout(transportServiceConfig.requestTimeout)
        .version(HttpClient.Version.HTTP_2)
        .build()
    val tubeData = TubeDataHttp(
        transportServiceConfig.toTflHttpClientConfig(),
        httpClient,
        TflPayloadParserHttp(json)
    )
    val tubeMetadataRepository = RealTubeMetadataRepository(tubeData)
    val tubeLocationEstimator = RealTubeLocationEstimator()
    val tubeSnapshotAssembler = RealTubeSnapshotAssembler(tubeLocationEstimator)

    return RealTubeSnapshotService(
        tubeData,
        tubeMetadataRepository,
        tubeSnapshotAssembler,
        Clock.systemUTC(),
        transportServiceConfig.cacheTtl
    )
}
