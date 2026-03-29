import type {
  Coordinate,
  FeaturePickerFeature,
  FutureStationArrival,
  RailLine,
  RailLinePath,
  RailMapDynamicState,
  RailMapSnapshot,
  RailService,
  RailStation,
  StationArrival,
  TransportErrorPayload
} from './types'

export function railMapSnapshotFromUnknown(value: unknown): RailMapSnapshot {
  if (isRailMapSnapshot(value)) {
    return value
  }

  throw new Error('Invalid rail map snapshot payload.')
}

export function railMapDynamicStateFromUnknown(value: unknown): RailMapDynamicState {
  if (isRailMapDynamicState(value)) {
    return value
  }

  throw new Error('Invalid rail map update payload.')
}

export function transportErrorMessageFromUnknown(value: unknown, fallbackMessage: string): string {
  if (isTransportErrorPayload(value) && value.message != null) {
    return value.message
  }

  return fallbackMessage
}

function isRailMapSnapshot(value: unknown): value is RailMapSnapshot {
  if (!isRailMapDynamicState(value) || !isRecord(value)) {
    return false
  }

  return isArrayOf(value.lines, isRailLine)
}

function isRailMapDynamicState(value: unknown): value is RailMapDynamicState {
  return (
    isRecord(value) &&
    isString(value.source) &&
    isString(value.generatedAt) &&
    isBoolean(value.cached) &&
    isFiniteNumber(value.cacheAgeSeconds) &&
    isFiniteNumber(value.stationsQueried) &&
    isFiniteNumber(value.stationsFailed) &&
    isBoolean(value.partial) &&
    isFiniteNumber(value.serviceCount) &&
    isArrayOf(value.stations, isRailStation) &&
    isArrayOf(value.services, isRailService)
  )
}

function isTransportErrorPayload(value: unknown): value is TransportErrorPayload {
  return isRecord(value) && (value.message == null || isString(value.message))
}

function isRailLine(value: unknown): value is RailLine {
  return isRecord(value) && isString(value.id) && isString(value.name) && isArrayOf(value.paths, isRailLinePath)
}

function isRailLinePath(value: unknown): value is RailLinePath {
  return isRecord(value) && isArrayOf(value.coordinates, isCoordinate)
}

function isFutureStationArrival(value: unknown): value is FutureStationArrival {
  return (
    isRecord(value) &&
    (value.stationId == null || isString(value.stationId)) &&
    isString(value.stationName) &&
    isString(value.expectedArrival)
  )
}

function isStationArrival(value: unknown): value is StationArrival {
  return (
    isRecord(value) &&
    isString(value.serviceId) &&
    isString(value.lineId) &&
    (value.destinationName == null || isString(value.destinationName)) &&
    isString(value.expectedArrival)
  )
}

function isRailService(value: unknown): value is RailService {
  return (
    isRecord(value) &&
    isString(value.serviceId) &&
    isString(value.vehicleId) &&
    isString(value.lineId) &&
    isString(value.lineName) &&
    (value.direction == null || isString(value.direction)) &&
    (value.destinationName == null || isString(value.destinationName)) &&
    (value.towards == null || isString(value.towards)) &&
    isString(value.currentLocation) &&
    (value.coordinate == null || isCoordinate(value.coordinate)) &&
    (value.headingDegrees == null || isFiniteNumber(value.headingDegrees)) &&
    (value.expectedArrival == null || isString(value.expectedArrival)) &&
    isString(value.observedAt) &&
    (value.futureArrivals == null || isArrayOf(value.futureArrivals, isFutureStationArrival))
  )
}

function isRailStation(value: unknown): value is RailStation {
  return (
    isRecord(value) &&
    isString(value.id) &&
    isString(value.name) &&
    isCoordinate(value.coordinate) &&
    isArrayOf(value.lineIds, isString) &&
    (value.arrivals == null || isArrayOf(value.arrivals, isStationArrival))
  )
}

function isCoordinate(value: unknown): value is Coordinate {
  return isRecord(value) && isFiniteNumber(value.lat) && isFiniteNumber(value.lon)
}

function isArrayOf<T>(value: unknown, itemGuard: (item: unknown) => item is T): value is T[] {
  return Array.isArray(value) && value.every(itemGuard)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null
}

function isString(value: unknown): value is string {
  return typeof value === 'string'
}

function isBoolean(value: unknown): value is boolean {
  return typeof value === 'boolean'
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}
