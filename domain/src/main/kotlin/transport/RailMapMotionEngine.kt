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
    private val trainStates = linkedMapOf<TrainId, TrainMotionState>()

    override fun observe(snapshot: RailMapSnapshot) =
        run {
            val lineIndex = snapshot.lines.associateBy(RailLine::id)
            val activeTrainIds = snapshot.trains.map(RailMapTrain::trainId).toSet()

            snapshot.trains.forEach { train ->
                observeTrain(train, lineIndex[train.lineId], snapshot.generatedAt)
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
                snapshot.source,
                snapshot.generatedAt,
                snapshot.cached,
                snapshot.cacheAge,
                snapshot.stationsQueried,
                snapshot.stationsFailed,
                snapshot.partial,
                snapshot.trainCount,
                snapshot.lines,
                snapshot.stations,
                snapshot.trains.map { train ->
                    advanceTrain(train, projectedLines[train.lineId], currentTime)
                }
            )
        }

    private fun observeTrain(
        train: RailMapTrain,
        line: RailLine?,
        observedAt: Instant
    ) {
        when (val nextStop = train.nextStop) {
            null -> trainStates.remove(train.trainId)
            else -> observeTrainWithNextStop(train, line, observedAt, nextStop)
        }
    }

    private fun observeTrainWithNextStop(
        train: RailMapTrain,
        line: RailLine?,
        observedAt: Instant,
        nextStop: StationReference
    ) {
        trainStates[train.trainId] =
            observedTrainState(train, line, observedAt, nextStop)
    }

    private fun observedTrainState(
        train: RailMapTrain,
        line: RailLine?,
        observedAt: Instant,
        nextStop: StationReference
    ) =
        trainStates[train.trainId]
            ?.let { previousState ->
                when {
                    previousState.currentNextStop.id == nextStop.id ->
                        updatedTrainState(train, line, nextStop, previousState)
                    else ->
                        transitionedTrainState(train, line, observedAt, nextStop, previousState)
                }
            }
            ?: initialTrainState(train, nextStop)

    private fun initialTrainState(
        train: RailMapTrain,
        nextStop: StationReference
    ) =
        TrainMotionState(
            train.direction,
            null,
            null,
            nextStop,
            train.expectedArrival
        )

    private fun updatedTrainState(
        train: RailMapTrain,
        line: RailLine?,
        nextStop: StationReference,
        previousState: TrainMotionState
    ): TrainMotionState {
        val currentDirection = train.direction ?: previousState.direction
        val retainedDeparture = retainedDeparture(previousState, line, currentDirection, nextStop)

        return TrainMotionState(
            currentDirection,
            retainedDeparture?.station,
            retainedDeparture?.departedAt,
            nextStop,
            train.expectedArrival
        )
    }

    private fun transitionedTrainState(
        train: RailMapTrain,
        line: RailLine?,
        observedAt: Instant,
        nextStop: StationReference,
        previousState: TrainMotionState
    ): TrainMotionState {
        val currentDirection = train.direction ?: previousState.direction
        val departure =
            previousState.currentNextStop
                .takeIf { previousNextStop ->
                    hasAdjacentStopPair(line, currentDirection, previousNextStop.id, nextStop.id)
                }
                ?.let { previousNextStop ->
                    TrainDepartureState(previousNextStop, observedAt)
                }

        return TrainMotionState(
            currentDirection,
            departure?.station,
            departure?.departedAt,
            nextStop,
            train.expectedArrival
        )
    }

    private fun retainedDeparture(
        previousState: TrainMotionState,
        line: RailLine?,
        direction: TrainDirection?,
        nextStop: StationReference
    ) =
        departureState(previousState)
            ?.takeIf { departure ->
                hasAdjacentStopPair(line, direction, departure.station.id, nextStop.id)
            }

    private fun advanceTrain(
        train: RailMapTrain,
        projectedLine: RailLineProjection?,
        currentTime: Instant
    ): RailMapTrain {
        val projection = projectedTrainPosition(train, projectedLine, currentTime)

        return if (projection == null) {
            train
        } else {
            train.copy(
                coordinate = projection.coordinate,
                heading = projection.heading
            )
        }
    }

    private fun projectedTrainPosition(
        train: RailMapTrain,
        projectedLine: RailLineProjection?,
        currentTime: Instant
    ): TrainMapProjection? {
        val trainState = trainStates[train.trainId]
        val departure = trainState?.let(::departureState)
        val currentNextStop = train.nextStop
        val travelDuration =
            trainState?.let { state ->
                departure?.let { departed -> travelDuration(departed, state) }
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

    private fun departureState(state: TrainMotionState) =
        state.departedFrom
            ?.let { departedFrom ->
                state.departedAt?.let { departedAt ->
                    TrainDepartureState(departedFrom, departedAt)
                }
            }

    private fun travelDuration(
        departed: TrainDepartureState,
        state: TrainMotionState
    ) =
        state.currentExpectedArrival
            ?.let { expectedArrival ->
                Duration.between(departed.departedAt, expectedArrival)
                    .takeUnless { duration -> duration.isNegative || duration.isZero }
            }

    private fun elapsedSinceDeparture(
        departed: TrainDepartureState,
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
        direction: TrainDirection?,
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
        direction: TrainDirection?,
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
        direction: TrainDirection?
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

data class TrainMotionState(
    val direction: TrainDirection?,
    val departedFrom: StationReference?,
    val departedAt: Instant?,
    val currentNextStop: StationReference,
    val currentExpectedArrival: Instant?
)

data class TrainDepartureState(
    val station: StationReference,
    val departedAt: Instant
)
