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
    private val segmentDurations = linkedMapOf<SegmentKey, SegmentDurationSamples>()
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
        val previousState = trainStates[train.trainId]

        when {
            previousState == null ->
                trainStates[train.trainId] =
                    TrainMotionState(
                        train.lineId,
                        train.direction,
                        null,
                        nextStop,
                        observedAt
                    )
            previousState.currentNextStop.id == nextStop.id ->
                trainStates[train.trainId] =
                    previousState.copy(
                        lineId = train.lineId,
                        direction = train.direction ?: previousState.direction,
                        currentNextStop = nextStop
                    )
            else ->
                trainStates[train.trainId] =
                    transitionedTrainState(train, line, observedAt, nextStop, previousState)
        }
    }

    private fun transitionedTrainState(
        train: RailMapTrain,
        line: RailLine?,
        observedAt: Instant,
        nextStop: StationReference,
        previousState: TrainMotionState
    ): TrainMotionState {
        maybeRecordSegmentDuration(train, line, observedAt, previousState)

        val currentDirection = train.direction ?: previousState.direction
        val previousNextStop =
            if (hasAdjacentStopPair(line, currentDirection, previousState.currentNextStop.id, nextStop.id)) {
                previousState.currentNextStop
            } else {
                null
            }

        return TrainMotionState(
            train.lineId,
            currentDirection,
            previousNextStop,
            nextStop,
            observedAt
        )
    }

    private fun maybeRecordSegmentDuration(
        train: RailMapTrain,
        line: RailLine?,
        observedAt: Instant,
        previousState: TrainMotionState
    ) {
        val previousDirection = previousState.direction ?: train.direction
        val sampleDuration = Duration.between(previousState.currentNextStopObservedAt, observedAt)
        val canRecordSample =
            previousState.previousNextStop != null &&
                line != null &&
                isAdjacentStopPair(
                    line,
                    previousDirection,
                    previousState.previousNextStop.id,
                    previousState.currentNextStop.id
                ) &&
                !sampleDuration.isNegative &&
                !sampleDuration.isZero

        if (canRecordSample) {
            val segmentKey = SegmentKey(
                train.lineId,
                previousState.previousNextStop.id,
                previousState.currentNextStop.id
            )
            val existing = segmentDurations[segmentKey] ?: SegmentDurationSamples(Duration.ZERO, 0)
            segmentDurations[segmentKey] = existing.addSample(sampleDuration)
        }
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
        val previousNextStop = trainState?.previousNextStop
        val currentNextStop = train.nextStop
        val learnedDuration =
            if (previousNextStop == null || currentNextStop == null) {
                null
            } else {
                segmentDurations[SegmentKey(train.lineId, previousNextStop.id, currentNextStop.id)]?.averageDuration()
            }
        val elapsed =
            if (trainState == null || learnedDuration == null) {
                null
            } else {
                Duration.between(trainState.currentNextStopObservedAt, currentTime)
                    .takeUnless(Duration::isNegative)
            }

        return if (
            previousNextStop == null ||
            currentNextStop == null ||
            learnedDuration == null ||
            projectedLine == null ||
            elapsed == null
        ) {
            null
        } else {
            val progress = elapsed.toMillis().toDouble() / learnedDuration.toMillis().coerceAtLeast(1).toDouble()
            projectedLine.projectBetweenStationsAtProgress(previousNextStop, currentNextStop, progress)
        }
    }

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
    val lineId: LineId,
    val direction: TrainDirection?,
    val previousNextStop: StationReference?,
    val currentNextStop: StationReference,
    val currentNextStopObservedAt: Instant
)

data class SegmentKey(
    val lineId: LineId,
    val fromStationId: StationId,
    val toStationId: StationId
)

data class SegmentDurationSamples(
    val totalDuration: Duration,
    val sampleCount: Int
) {
    fun addSample(duration: Duration) =
        SegmentDurationSamples(totalDuration.plus(duration), sampleCount + 1)

    fun averageDuration(): Duration =
        if (sampleCount <= 0) {
            Duration.ZERO
        } else {
            Duration.ofMillis(totalDuration.toMillis() / sampleCount.toLong())
        }
}
