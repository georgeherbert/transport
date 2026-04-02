export interface Coordinate {
  lat: number
  lon: number
}

export interface RailLinePath {
  coordinates: Coordinate[]
}

export interface RailLine {
  id: string
  name: string
  paths: RailLinePath[]
}

export interface FutureStationArrival {
  stationId: string | null
  stationName: string
  expectedArrival: string
}

export interface StationArrival {
  serviceId: string
  lineId: string
  destinationName: string | null
  expectedArrival: string
}

export interface RailService {
  serviceId: string
  lineId: string
  lineName: string
  destinationName: string | null
  towards: string | null
  currentLocation: string
  coordinate: Coordinate | null
  headingDegrees: number | null
  futureArrivals: FutureStationArrival[] | null
}

export interface PlottedRailService extends RailService {
  coordinate: Coordinate
}

export interface RailStation {
  id: string
  name: string
  coordinate: Coordinate
  lineIds: string[]
  arrivals: StationArrival[] | null
}

export interface RailMapDynamicState {
  generatedAt: string
  serviceCount: number
  stations: RailStation[]
  services: RailService[]
}

export interface RailMapSnapshot extends RailMapDynamicState {
  lines: RailLine[]
}

export interface SelectedStationFeature {
  kind: 'station'
  id: string
}

export interface SelectedServiceFeature {
  kind: 'service'
  id: string
}

export type SelectedMapFeature = SelectedStationFeature | SelectedServiceFeature

export interface FeaturePickerFeature {
  kind: 'station' | 'service'
  id: string
  title: string
  detail: string
  accentColor: string | null
}

export interface FeaturePickerState {
  lat: number
  lon: number
  features: FeaturePickerFeature[]
}

export interface TransportErrorPayload {
  message?: string
}

export type SelectedLineId = 'all' | string
