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
        val nextStop = train.nextStop ?: run {
            trainStates.remove(train.trainId)
            return
        }

        val previousState = trainStates[train.trainId]
        if (previousState == null) {
            trainStates[train.trainId] =
                TrainMotionState(
                    train.lineId,
                    train.direction,
                    null,
                    nextStop,
                    observedAt
                )
            return
        }

        if (previousState.currentNextStop.id == nextStop.id) {
            trainStates[train.trainId] =
                previousState.copy(
                    lineId = train.lineId,
                    direction = train.direction ?: previousState.direction,
                    currentNextStop = nextStop
                )
            return
        }

        val previousDirection = previousState.direction ?: train.direction
        if (previousState.previousNextStop != null &&
            line != null &&
            isAdjacentStopPair(line, previousDirection, previousState.previousNextStop.id, previousState.currentNextStop.id)
        ) {
            val sampleDuration = Duration.between(previousState.currentNextStopObservedAt, observedAt)
            if (!sampleDuration.isNegative && !sampleDuration.isZero) {
                val segmentKey = SegmentKey(train.lineId, previousState.previousNextStop.id, previousState.currentNextStop.id)
                val existing = segmentDurations[segmentKey] ?: SegmentDurationSamples(Duration.ZERO, 0)
                segmentDurations[segmentKey] = existing.addSample(sampleDuration)
            }
        }

        val currentDirection = train.direction ?: previousState.direction
        val previousNextStop =
            if (line != null && isAdjacentStopPair(line, currentDirection, previousState.currentNextStop.id, nextStop.id)) {
                previousState.currentNextStop
            } else {
                null
            }

        trainStates[train.trainId] =
            TrainMotionState(
                train.lineId,
                currentDirection,
                previousNextStop,
                nextStop,
                observedAt
            )
    }

    private fun advanceTrain(
        train: RailMapTrain,
        projectedLine: RailLineProjection?,
        currentTime: Instant
    ): RailMapTrain {
        val trainState = trainStates[train.trainId] ?: return train
        val previousNextStop = trainState.previousNextStop ?: return train
        val currentNextStop = train.nextStop ?: return train
        val learnedDuration =
            segmentDurations[SegmentKey(train.lineId, previousNextStop.id, currentNextStop.id)]?.averageDuration()
                ?: return train
        val projectionLine = projectedLine ?: return train
        val elapsed = Duration.between(trainState.currentNextStopObservedAt, currentTime)
        if (elapsed.isNegative) {
            return train
        }

        val progress = elapsed.toMillis().toDouble() / learnedDuration.toMillis().coerceAtLeast(1).toDouble()
        val projection = projectionLine.projectBetweenStationsAtProgress(previousNextStop, currentNextStop, progress)
            ?: return train

        return train.copy(
            coordinate = projection.coordinate,
            heading = projection.heading
        )
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
    ): List<RailLineSequence> {
        if (line.sequences.isEmpty()) {
            return emptyList()
        }

        if (direction == null) {
            return line.sequences
        }

        val matchingDirections = line.sequences.filter { sequence -> sequence.direction == direction }
        return if (matchingDirections.isNotEmpty()) matchingDirections else line.sequences
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
