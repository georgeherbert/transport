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
import io.ktor.server.response.respondTextWriter
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.collect

fun Application.transportModule(
    railSnapshotService: RailSnapshotService,
    railLineMapService: RailLineMapService,
    railMapFeedService: RailMapFeedService,
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
            call.respondMap(forceRefresh, railMapFeedService, serviceResponseMapper)
        }

        get("/api/tubes/map") {
            val forceRefresh = call.request.queryParameters["refresh"]?.equals("true", true) == true
            call.respondMap(forceRefresh, railMapFeedService, serviceResponseMapper)
        }

        get("/api/rail/map/stream") {
            call.respondMapStream(railMapFeedService, serviceResponseMapper, transportJson)
        }

        get("/api/tubes/map/stream") {
            call.respondMapStream(railMapFeedService, serviceResponseMapper, transportJson)
        }

        get("/api/rail/lines") {
            call.respondLineMap(railLineMapService, serviceResponseMapper)
        }

        get("/api/tubes/lines") {
            call.respondLineMap(railLineMapService, serviceResponseMapper)
        }

        get("/api/rail/live") {
            val forceRefresh = call.request.queryParameters["refresh"]?.equals("true", true) == true
            call.respondSnapshot(forceRefresh, railSnapshotService, serviceResponseMapper)
        }

        get("/api/tubes/live") {
            val forceRefresh = call.request.queryParameters["refresh"]?.equals("true", true) == true
            call.respondSnapshot(forceRefresh, railSnapshotService, serviceResponseMapper)
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
    railMapFeedService: RailMapFeedService,
    serviceResponseMapper: ServiceResponseMapper
) =
    when (val mapResult = railMapFeedService.getRailMap(forceRefresh)) {
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
                is RailMapFeedUpdate.TrainPositionsUpdated -> {
                    writeSseEvent(
                        "train_positions",
                        transportJson.encodeToString(serviceResponseMapper.trainPositionsResponse(update.trainPositions))
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

private suspend fun io.ktor.server.application.ApplicationCall.respondLineMap(
    railLineMapService: RailLineMapService,
    serviceResponseMapper: ServiceResponseMapper
) =
    when (val lineMapResult = railLineMapService.getRailLineMap()) {
        is Success -> respond(serviceResponseMapper.lineMapResponse(lineMapResult.value))
        is Failure -> respond(
            httpStatus(lineMapResult.reason),
            serviceResponseMapper.errorResponse(lineMapResult.reason)
        )
    }

private suspend fun io.ktor.server.application.ApplicationCall.respondSnapshot(
    forceRefresh: Boolean,
    railSnapshotService: RailSnapshotService,
    serviceResponseMapper: ServiceResponseMapper
) =
    when (val snapshotResult = railSnapshotService.getLiveSnapshot(forceRefresh)) {
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

private fun Appendable.writeSseEvent(event: String, data: String) {
    append("event: ").append(event).append('\n')
    data.lines().forEach { line ->
        append("data: ").append(line).append('\n')
    }
    append('\n')
}
