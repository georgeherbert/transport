package transport

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Application.transportModule(
    railMapFeedService: RailMapFeedService,
    serviceResponseMapper: ServiceResponseMapper,
    transportJson: Json
) {
    install(ContentNegotiation) {
        json(transportJson)
    }

    routing {
        get("/api/rail/map") {
            call.respondMap(railMapFeedService, serviceResponseMapper)
        }

        get("/api/rail/map/stream") {
            call.respondMapStream(railMapFeedService, serviceResponseMapper, transportJson)
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
    railMapFeedService: RailMapFeedService,
    serviceResponseMapper: ServiceResponseMapper
) =
    when (val mapResult = railMapFeedService.getRailMap(false)) {
        is Success -> respond(serviceResponseMapper.mapResponse(mapResult.value))
        is Failure -> respond(
            httpStatus(mapResult.reason),
            serviceResponseMapper.errorResponse(mapResult.reason)
        )
    }

private suspend fun io.ktor.server.application.ApplicationCall.respondMapStream(
    railMapFeedService: RailMapFeedService,
    serviceResponseMapper: ServiceResponseMapper,
    transportJson: Json
) =
    respondTextWriter(ContentType.parse("text/event-stream")) {
        write("retry: 5000\n\n")
        flush()

        when (val currentSnapshot = railMapFeedService.getRailMap(false)) {
            is Success -> {
                writeSseEvent(
                    "snapshot",
                    transportJson.encodeToString(serviceResponseMapper.mapResponse(currentSnapshot.value))
                )
                flush()
            }
            is Failure -> Unit
        }

        railMapFeedService.currentError()?.let { error ->
            writeSseEvent(
                "transport_error",
                transportJson.encodeToString(serviceResponseMapper.errorResponse(error))
            )
            flush()
        }

        railMapFeedService.updates().collect { update ->
            when (update) {
                is RailMapFeedUpdate.SnapshotUpdated -> {
                    writeSseEvent(
                        "snapshot",
                        transportJson.encodeToString(serviceResponseMapper.mapResponse(update.snapshot))
                    )
                    flush()
                }
                is RailMapFeedUpdate.ServicePositionsUpdated -> {
                    writeSseEvent(
                        "service_positions",
                        transportJson.encodeToString(serviceResponseMapper.servicePositionsResponse(update.servicePositions))
                    )
                    flush()
                }
                is RailMapFeedUpdate.ErrorUpdated -> {
                    writeSseEvent(
                        "transport_error",
                        transportJson.encodeToString(serviceResponseMapper.errorResponse(update.error))
                    )
                    flush()
                }
            }
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

private fun Appendable.writeSseEvent(event: String, data: String) {
    append("event: ").append(event).append('\n')
    data.lines().forEach { line ->
        append("data: ").append(line).append('\n')
    }
    append('\n')
}
