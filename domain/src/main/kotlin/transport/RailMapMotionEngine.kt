package transport

import java.time.Duration
import java.time.Instant

interface RailMapMotionEngine {
    fun observe(snapshot: RailMapSnapshot): RailMapSnapshot
    fun advance(snapshot: RailMapSnapshot, currentTime: Instant): RailMapSnapshot
}

class RealRailMapMotionEngine(
    private val railLineProjectionFactory: RailLineProjectionFactory
) : RailMapMotionEngine {
    private val trainStates = linkedMapOf<ServiceId, ServiceMotionState>()

    override fun observe(snapshot: RailMapSnapshot) =
        run {
            val lineIndex = snapshot.lines.associateBy(RailLine::id)
            val activeTrainIds = snapshot.services.map(RailMapService::serviceId).toSet()

            snapshot.services.forEach { service ->
                observeService(service, lineIndex[service.lineId], snapshot.generatedAt)
            }

            trainStates.keys.retainAll(activeTrainIds)

            advance(snapshot, snapshot.generatedAt)
        }

    override fun advance(snapshot: RailMapSnapshot, currentTime: Instant) =
        run {
            val projectedLines = snapshot.lines.associateBy(RailLine::id) { line ->
                railLineProjectionFactory.create(line)
            }

            RailMapSnapshot(
                snapshot.generatedAt,
                snapshot.stationsFailed,
                snapshot.partial,
                snapshot.serviceCount,
                snapshot.lines,
                snapshot.stations,
                snapshot.services.map { service ->
                    advanceService(service, projectedLines[service.lineId], currentTime)
                }
            )
        }

    private fun observeService(
        service: RailMapService,
        line: RailLine?,
        observedAt: Instant
    ) {
        when (val nextStop = service.nextStop) {
            null -> trainStates.remove(service.serviceId)
            else -> observeServiceWithNextStop(service, line, observedAt, nextStop)
        }
    }

    private fun observeServiceWithNextStop(
        service: RailMapService,
        line: RailLine?,
        observedAt: Instant,
        nextStop: StationReference
    ) {
        trainStates[service.serviceId] =
            observedServiceState(service, line, observedAt, nextStop)
    }

    private fun observedServiceState(
        service: RailMapService,
        line: RailLine?,
        observedAt: Instant,
        nextStop: StationReference
    ) =
        trainStates[service.serviceId]
            ?.let { previousState ->
                when {
                    previousState.currentNextStop.id == nextStop.id ->
                        updatedServiceState(service, line, nextStop, previousState)
                    else ->
                        transitionedServiceState(service, line, observedAt, nextStop, previousState)
                }
            }
            ?: initialServiceState(service, nextStop)

    private fun initialServiceState(
        service: RailMapService,
        nextStop: StationReference
    ) =
        ServiceMotionState(
            service.direction,
            null,
            null,
            nextStop
        )

    private fun updatedServiceState(
        service: RailMapService,
        line: RailLine?,
        nextStop: StationReference,
        previousState: ServiceMotionState
    ): ServiceMotionState {
        val currentDirection = service.direction ?: previousState.direction
        val retainedDeparture = retainedDeparture(previousState, line, currentDirection, nextStop)

        return ServiceMotionState(
            currentDirection,
            retainedDeparture?.station,
            retainedDeparture?.departedAt,
            nextStop
        )
    }

    private fun transitionedServiceState(
        service: RailMapService,
        line: RailLine?,
        observedAt: Instant,
        nextStop: StationReference,
        previousState: ServiceMotionState
    ): ServiceMotionState {
        val currentDirection = service.direction ?: previousState.direction
        val departure =
            previousState.currentNextStop
                .takeIf { previousNextStop ->
                    hasAdjacentStopPair(line, currentDirection, previousNextStop.id, nextStop.id)
                }
                ?.let { previousNextStop ->
                    ServiceDepartureState(previousNextStop, observedAt)
                }

        return ServiceMotionState(
            currentDirection,
            departure?.station,
            departure?.departedAt,
            nextStop
        )
    }

    private fun retainedDeparture(
        previousState: ServiceMotionState,
        line: RailLine?,
        direction: ServiceDirection?,
        nextStop: StationReference
    ) =
        departureState(previousState)
            ?.takeIf { departure ->
                hasAdjacentStopPair(line, direction, departure.station.id, nextStop.id)
            }

    private fun advanceService(
        service: RailMapService,
        projectedLine: RailLineProjection?,
        currentTime: Instant
    ): RailMapService {
        val projection = projectedServicePosition(service, projectedLine, currentTime)

        return if (projection == null) {
            service
        } else {
            service.copy(
                coordinate = projection.coordinate,
                heading = projection.heading
            )
        }
    }

    private fun projectedServicePosition(
        service: RailMapService,
        projectedLine: RailLineProjection?,
        currentTime: Instant
    ): ServiceMapProjection? {
        val trainState = trainStates[service.serviceId]
        val departure = trainState?.let(::departureState)
        val currentNextStop = service.nextStop
        val travelDuration =
            service.expectedArrival
                ?.let { expectedArrival ->
                    departure?.let { departed -> travelDuration(departed, expectedArrival) }
                }
        val elapsed = departure?.let { departed -> elapsedSinceDeparture(departed, currentTime) }

        return if (
            departure == null ||
            currentNextStop == null ||
            travelDuration == null ||
            projectedLine == null ||
            elapsed == null
        ) {
            null
        } else {
            projectedLine.projectBetweenStationsAtProgress(
                departure.station,
                currentNextStop,
                progress(elapsed, travelDuration)
            )
        }
    }

    private fun departureState(state: ServiceMotionState) =
        state.departedFrom
            ?.let { departedFrom ->
                state.departedAt?.let { departedAt ->
                    ServiceDepartureState(departedFrom, departedAt)
                }
            }

    private fun travelDuration(
        departed: ServiceDepartureState,
        expectedArrival: Instant
    ) =
        Duration.between(departed.departedAt, expectedArrival)
            .takeUnless { duration -> duration.isNegative || duration.isZero }

    private fun elapsedSinceDeparture(
        departed: ServiceDepartureState,
        currentTime: Instant
    ) =
        Duration.between(departed.departedAt, currentTime)
            .takeUnless(Duration::isNegative)

    private fun progress(
        elapsed: Duration,
        travelDuration: Duration
    ) =
        elapsed.toMillis().toDouble() / travelDuration.toMillis().toDouble()

    private fun hasAdjacentStopPair(
        line: RailLine?,
        direction: ServiceDirection?,
        fromStationId: StationId,
        toStationId: StationId
    ): Boolean =
        if (line != null) {
            isAdjacentStopPair(line, direction, fromStationId, toStationId)
        } else {
            false
        }

    private fun isAdjacentStopPair(
        line: RailLine,
        direction: ServiceDirection?,
        fromStationId: StationId,
        toStationId: StationId
    ): Boolean =
        matchingSequences(line, direction)
            .any { sequence ->
                sequence.stations
                    .zipWithNext()
                    .any { (fromStation, toStation) ->
                        fromStation.id == fromStationId && toStation.id == toStationId
                    }
            }

    private fun matchingSequences(
        line: RailLine,
        direction: ServiceDirection?
    ) =
        when {
            line.sequences.isEmpty() -> emptyList()
            direction == null -> line.sequences
            else ->
                line.sequences.filter { sequence -> sequence.direction == direction }
                    .let { matchingDirections ->
                        if (matchingDirections.isNotEmpty()) matchingDirections else line.sequences
                    }
        }
}

data class ServiceMotionState(
    val direction: ServiceDirection?,
    val departedFrom: StationReference?,
    val departedAt: Instant?,
    val currentNextStop: StationReference
)

data class ServiceDepartureState(
    val station: StationReference,
    val departedAt: Instant
)
