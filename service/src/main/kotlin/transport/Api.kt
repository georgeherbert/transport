package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.http.content.staticResources
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.time.Instant
import kotlinx.serialization.json.Json

fun Application.transportModule(
    tubeSnapshotService: TubeSnapshotService,
    tubeLineMapService: TubeLineMapService,
    tubeMapService: TubeMapService,
    serviceResponseMapper: ServiceResponseMapper,
    transportJson: Json
) {
    install(ContentNegotiation) {
        json(transportJson)
    }

    routing {
        get("/api") {
            call.respond(serviceResponseMapper.apiDescription())
        }

        get("/health") {
            call.respond(serviceResponseMapper.healthResponse(Instant.now()))
        }

        get("/api/rail/map") {
            val forceRefresh = call.request.queryParameters["refresh"]?.equals("true", true) == true
            call.respondMap(forceRefresh, tubeMapService, serviceResponseMapper)
        }

        get("/api/tubes/map") {
            val forceRefresh = call.request.queryParameters["refresh"]?.equals("true", true) == true
            call.respondMap(forceRefresh, tubeMapService, serviceResponseMapper)
        }

        get("/api/rail/lines") {
            call.respondLineMap(tubeLineMapService, serviceResponseMapper)
        }

        get("/api/tubes/lines") {
            call.respondLineMap(tubeLineMapService, serviceResponseMapper)
        }

        get("/api/rail/live") {
            val forceRefresh = call.request.queryParameters["refresh"]?.equals("true", true) == true
            call.respondSnapshot(forceRefresh, tubeSnapshotService, serviceResponseMapper)
        }

        get("/api/tubes/live") {
            val forceRefresh = call.request.queryParameters["refresh"]?.equals("true", true) == true
            call.respondSnapshot(forceRefresh, tubeSnapshotService, serviceResponseMapper)
        }

        get("/") {
            val indexHtml = checkNotNull(this::class.java.classLoader.getResourceAsStream("ui/index.html")) {
                "Missing UI index resource"
            }.readBytes()

            call.respondBytes(indexHtml, ContentType.Text.Html)
        }

        staticResources("/assets", "ui/assets")
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondMap(
    forceRefresh: Boolean,
    tubeMapService: TubeMapService,
    serviceResponseMapper: ServiceResponseMapper
) =
    when (val mapResult = tubeMapService.getTubeMap(forceRefresh)) {
        is Success -> respond(serviceResponseMapper.mapResponse(mapResult.value))
        is Failure -> respond(
            httpStatus(mapResult.reason),
            serviceResponseMapper.errorResponse(mapResult.reason)
        )
    }

private suspend fun io.ktor.server.application.ApplicationCall.respondLineMap(
    tubeLineMapService: TubeLineMapService,
    serviceResponseMapper: ServiceResponseMapper
) =
    when (val lineMapResult = tubeLineMapService.getTubeLineMap()) {
        is Success -> respond(serviceResponseMapper.lineMapResponse(lineMapResult.value))
        is Failure -> respond(
            httpStatus(lineMapResult.reason),
            serviceResponseMapper.errorResponse(lineMapResult.reason)
        )
    }

private suspend fun io.ktor.server.application.ApplicationCall.respondSnapshot(
    forceRefresh: Boolean,
    tubeSnapshotService: TubeSnapshotService,
    serviceResponseMapper: ServiceResponseMapper
) =
    when (val snapshotResult = tubeSnapshotService.getLiveSnapshot(forceRefresh)) {
        is Success -> respond(serviceResponseMapper.snapshotResponse(snapshotResult.value))
        is Failure -> respond(
            httpStatus(snapshotResult.reason),
            serviceResponseMapper.errorResponse(snapshotResult.reason)
        )
    }

fun httpStatus(error: TransportError) =
    when (error) {
        is TransportError.MetadataUnavailable -> HttpStatusCode.ServiceUnavailable
        is TransportError.SnapshotUnavailable -> HttpStatusCode.ServiceUnavailable
        is TransportError.UpstreamHttpFailure -> HttpStatusCode.BadGateway
        is TransportError.UpstreamNetworkFailure -> HttpStatusCode.BadGateway
        is TransportError.UpstreamPayloadFailure -> HttpStatusCode.BadGateway
    }
