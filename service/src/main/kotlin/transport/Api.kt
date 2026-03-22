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

        get("/api/tubes/live") {
            val forceRefresh = call.request.queryParameters["refresh"]?.equals("true", true) == true
            when (val snapshotResult = tubeSnapshotService.getLiveSnapshot(forceRefresh)) {
                is Success -> call.respond(serviceResponseMapper.snapshotResponse(snapshotResult.value))
                is Failure -> call.respond(
                    httpStatus(snapshotResult.reason),
                    serviceResponseMapper.errorResponse(snapshotResult.reason)
                )
            }
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

fun httpStatus(error: TransportError) =
    when (error) {
        is TransportError.MetadataUnavailable -> HttpStatusCode.ServiceUnavailable
        is TransportError.SnapshotUnavailable -> HttpStatusCode.ServiceUnavailable
        is TransportError.UpstreamHttpFailure -> HttpStatusCode.BadGateway
        is TransportError.UpstreamNetworkFailure -> HttpStatusCode.BadGateway
        is TransportError.UpstreamPayloadFailure -> HttpStatusCode.BadGateway
    }
