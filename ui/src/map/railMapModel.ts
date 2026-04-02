import type {
  PlottedRailService,
  RailMapSnapshot,
  RailService,
  RailStation,
  SelectedLineId
} from '../types'

export interface LineOption {
  id: string
  name: string
}

export interface VisibleLinePath {
  id: string
  lineId: string
  coordinates: RailStation['coordinate'][]
}

export function buildLineOptions(mapSnapshot: RailMapSnapshot | null): LineOption[] {
  if (mapSnapshot == null) {
    return []
  }

  return mapSnapshot.lines
    .map(line => ({
      id: line.id,
      name: line.name
    }))
    .sort((leftLine, rightLine) => leftLine.name.localeCompare(rightLine.name))
}

export function buildVisibleLinePaths(
  mapSnapshot: RailMapSnapshot | null,
  selectedLineId: SelectedLineId
): VisibleLinePath[] {
  if (mapSnapshot == null) {
    return []
  }

  return mapSnapshot.lines
    .filter(line => selectedLineId === 'all' || line.id === selectedLineId)
    .flatMap(line =>
      line.paths.map((path, index) => ({
        id: `${line.id}:${index}`,
        lineId: line.id,
        coordinates: path.coordinates
      }))
    )
}

export function buildVisibleServices(
  mapSnapshot: RailMapSnapshot | null,
  selectedLineId: SelectedLineId
): RailService[] {
  if (mapSnapshot == null) {
    return []
  }

  return mapSnapshot.services
    .filter(service => selectedLineId === 'all' || service.lineId === selectedLineId)
}

export function buildVisibleStations(
  mapSnapshot: RailMapSnapshot | null,
  selectedLineId: SelectedLineId
): RailStation[] {
  if (mapSnapshot == null) {
    return []
  }

  return mapSnapshot.stations
    .filter(station => selectedLineId === 'all' || station.lineIds.includes(selectedLineId))
    .sort((leftStation, rightStation) => leftStation.name.localeCompare(rightStation.name))
}

export function isPlottedService(service: RailService): service is PlottedRailService {
  return service.coordinate != null
}
