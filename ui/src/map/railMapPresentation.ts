import type { CSSProperties } from 'react'
import L, { type DivIcon, type Map as LeafletMap, type Point } from 'leaflet'

import type {
  Coordinate,
  FeaturePickerFeature,
  PlottedRailService,
  RailService,
  RailStation,
  SelectedLineId,
  SelectedMapFeature,
  StationArrival
} from '../types'

type AccentStyle = CSSProperties & { '--accent-color': string }

interface ClickableFeature extends FeaturePickerFeature {
  point: Point
}

const linePalette: Record<string, string> = {
  bakerloo: '#9b5a20',
  central: '#d7261b',
  circle: '#f4c430',
  district: '#117d37',
  elizabeth: '#6950a1',
  'hammersmith-city': '#e67ca6',
  jubilee: '#6d7b8a',
  liberty: '#6e7178',
  lioness: '#f3b21a',
  mildmay: '#3f7edb',
  metropolitan: '#7c1a63',
  northern: '#202124',
  piccadilly: '#1640b4',
  suffragette: '#2f8f5b',
  tram: '#7dc242',
  victoria: '#0097d7',
  'waterloo-city': '#67c6c2',
  weaver: '#8a3a2f',
  windrush: '#d64034'
}

const serviceIconCache = new Map<string, DivIcon>()
const stationIconCache = new Map<string, DivIcon>()
const serviceServiceOverlapRadiusPixels = 16
const serviceStationOverlapRadiusPixels = 14
const stationStationOverlapRadiusPixels = 10
const servicePopupAnchorPixels = -18

export const basemapAttribution =
  '&copy; <a href="https://carto.com/attributions" target="_blank" rel="noreferrer">CARTO</a> ' +
  '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">OpenStreetMap</a>'
export const basemapUrl = 'https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png'
export const featurePickerPopupOffset: [number, number] = [0, -12]
export const londonCenter: [number, number] = [51.5072, -0.1276]

export function accentStyle(accentColor: string): AccentStyle {
  return {
    '--accent-color': accentColor
  }
}

export function colorForLine(lineId: string): string {
  return linePalette[lineId] ?? '#1f6feb'
}

export function createServiceIcon(service: PlottedRailService): DivIcon {
  const lineColor = colorForLine(service.lineId)
  const headingDegrees = roundedHeadingForIcon(service.headingDegrees)
  const markerLabel = markerLabelForLine(service.lineId)
  const markerTextColor = markerTextColorForLine(service.lineId)
  const shapeMarkup =
    service.headingDegrees == null
      ? `
          <circle
            cx="22"
            cy="22"
            r="14"
            fill="${lineColor}"
            stroke="#ffffff"
            stroke-width="2.4"
          />
        `
      : `
          <g transform="rotate(${headingDegrees} 22 22)">
            <path
              d="M22 3 L29 10 A14 14 0 1 1 15 10 Z"
              fill="${lineColor}"
              stroke="#ffffff"
              stroke-width="2.4"
              stroke-linejoin="round"
            />
          </g>
        `
  const cacheKey = `${service.lineId}:${service.headingDegrees == null ? 'hidden' : headingDegrees}`
  const cachedIcon = serviceIconCache.get(cacheKey)

  if (cachedIcon != null) {
    return cachedIcon
  }

  const icon = L.divIcon({
    className: 'service-icon-shell',
    html: `
      <div class="service-marker">
        <svg class="service-marker-shape" viewBox="0 0 44 44" aria-hidden="true">
          ${shapeMarkup}
          <text
            x="22"
            y="24"
            fill="${markerTextColor}"
            font-size="9.2"
            font-weight="700"
            font-family="system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
            text-anchor="middle"
            dominant-baseline="middle"
          >
            ${markerLabel}
          </text>
        </svg>
      </div>
    `,
    iconSize: [44, 44],
    iconAnchor: [22, 22],
    popupAnchor: [0, servicePopupAnchorPixels]
  })

  serviceIconCache.set(cacheKey, icon)
  return icon
}

export function createStationIcon(station: RailStation, selectedLineId: SelectedLineId): DivIcon {
  const visibleLineIds =
    selectedLineId !== 'all' && station.lineIds.includes(selectedLineId)
      ? [selectedLineId]
      : sortedLineIds(station.lineIds)
  const cacheKey = `${selectedLineId}:${visibleLineIds.join('|')}`
  const cachedIcon = stationIconCache.get(cacheKey)

  if (cachedIcon != null) {
    return cachedIcon
  }

  const singleVisibleLineId = visibleLineIds[0]
  const shapeMarkup =
    visibleLineIds.length === 1 && singleVisibleLineId != null
      ? singleLineStationDot(singleVisibleLineId)
      : segmentedStationDot(visibleLineIds)
  const icon = L.divIcon({
    className: 'station-icon-shell',
    html: `
      <div class="station-marker">
        <svg class="station-marker-shape" viewBox="0 0 26 26" aria-hidden="true">
          ${shapeMarkup}
        </svg>
      </div>
    `,
    iconSize: [22, 22],
    iconAnchor: [11, 11],
    popupAnchor: [0, -10]
  })

  stationIconCache.set(cacheKey, icon)
  return icon
}

export function currentLocationLabelForService(service: RailService): string {
  return service.currentLocation
}

export function destinationLabelForService(service: RailService): string {
  return service.destinationName ?? 'Destination unavailable'
}

export function formatClockTime(value: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  }).format(new Date(value))
}

export function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    day: '2-digit',
    month: 'short'
  }).format(new Date(value))
}

export function leafletPosition(coordinate: Coordinate): [number, number] {
  return [coordinate.lat, coordinate.lon]
}

export function overlappingFeaturesForFeature(
  map: LeafletMap,
  clickedFeature: SelectedMapFeature,
  plottedServices: PlottedRailService[],
  visibleStations: RailStation[]
): FeaturePickerFeature[] {
  const clickableFeatures: ClickableFeature[] = [
    ...plottedServices.map(service => ({
      kind: 'service' as const,
      id: service.serviceId,
      title: `${service.lineName} service`,
      detail: destinationLabelForService(service),
      accentColor: colorForLine(service.lineId),
      point: map.latLngToContainerPoint(leafletPosition(service.coordinate))
    })),
    ...visibleStations.map(station => ({
      kind: 'station' as const,
      id: station.id,
      title: station.name,
      detail: stationDetailLabelForPicker(station),
      accentColor: null,
      point: map.latLngToContainerPoint(leafletPosition(station.coordinate))
    }))
  ]
  const clickedFeatureMatch = clickableFeatures.find(feature =>
    feature.kind === clickedFeature.kind && feature.id === clickedFeature.id
  )

  if (clickedFeatureMatch == null) {
    return []
  }

  return clickableFeatures
    .filter(feature => featuresOverlap(clickedFeatureMatch, feature))
    .map(({ point, ...feature }) => feature)
}

export function prioritizedOverlapFeatures(
  features: FeaturePickerFeature[],
  clickedFeature: SelectedMapFeature
): FeaturePickerFeature[] {
  return [...features].sort((leftFeature, rightFeature) => {
    if (leftFeature.kind === clickedFeature.kind && leftFeature.id === clickedFeature.id) {
      return -1
    }

    if (rightFeature.kind === clickedFeature.kind && rightFeature.id === clickedFeature.id) {
      return 1
    }

    if (leftFeature.kind !== rightFeature.kind) {
      return leftFeature.kind === 'service' ? -1 : 1
    }

    return leftFeature.title.localeCompare(rightFeature.title)
  })
}

export function prettifyLineId(lineId: string): string {
  return lineId
    .split('-')
    .map(word => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
}

export function sortedLineIds(lineIds: string[]): string[] {
  return [...lineIds].sort((leftLineId, rightLineId) =>
    prettifyLineId(leftLineId).localeCompare(prettifyLineId(rightLineId))
  )
}

export function stationArrivalLabelFor(arrival: StationArrival): string {
  return arrival.destinationName ?? 'Destination unavailable'
}

function featuresOverlap(leftFeature: ClickableFeature, rightFeature: ClickableFeature): boolean {
  return pointDistance(leftFeature.point, rightFeature.point) <= overlapRadiusFor(leftFeature.kind, rightFeature.kind)
}

function markerLabelForLine(lineId: string): string {
  const words = prettifyLineId(lineId).split(' ')
  const firstWord = words[0]

  if (words.length === 1 && firstWord != null) {
    return firstWord.slice(0, 2).toUpperCase()
  }

  return words.map(word => word.charAt(0)).join('').slice(0, 2).toUpperCase()
}

function markerTextColorForLine(lineId: string): string {
  const [red, green, blue] = rgbComponentsForHex(colorForLine(lineId))
  const luminance = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255

  if (luminance > 0.62) {
    return '#14213d'
  }

  return '#ffffff'
}

function overlapRadiusFor(leftKind: FeaturePickerFeature['kind'], rightKind: FeaturePickerFeature['kind']): number {
  if (leftKind === 'service' && rightKind === 'service') {
    return serviceServiceOverlapRadiusPixels
  }

  if (leftKind === 'station' && rightKind === 'station') {
    return stationStationOverlapRadiusPixels
  }

  return serviceStationOverlapRadiusPixels
}

function pointDistance(leftPoint: Point, rightPoint: Point): number {
  const deltaX = leftPoint.x - rightPoint.x
  const deltaY = leftPoint.y - rightPoint.y

  return Math.sqrt((deltaX * deltaX) + (deltaY * deltaY))
}

function rgbComponentsForHex(hexColor: string): [number, number, number] {
  const normalizedHex = hexColor.replace('#', '')

  if (normalizedHex.length !== 6) {
    return [31, 41, 51]
  }

  return [
    Number.parseInt(normalizedHex.slice(0, 2), 16),
    Number.parseInt(normalizedHex.slice(2, 4), 16),
    Number.parseInt(normalizedHex.slice(4, 6), 16)
  ]
}

function roundedHeadingForIcon(headingDegrees: number | null): number {
  if (headingDegrees == null) {
    return 0
  }

  return Math.round(headingDegrees)
}

function segmentedStationDot(lineIds: string[]): string {
  return lineIds
    .map((lineId, index) => {
      const startAngle = (-Math.PI / 2) + ((Math.PI * 2 * index) / lineIds.length)
      const endAngle = (-Math.PI / 2) + ((Math.PI * 2 * (index + 1)) / lineIds.length)

      return stationSegmentPath(startAngle, endAngle, colorForLine(lineId))
    })
    .join('')
}

function singleLineStationDot(lineId: string): string {
  return `
    <circle
      cx="13"
      cy="13"
      r="8.4"
      fill="${colorForLine(lineId)}"
      stroke="#ffffff"
      stroke-width="1.7"
    />
  `
}

function stationDetailLabelForPicker(station: RailStation): string {
  return `${station.lineIds.length} ${station.lineIds.length === 1 ? 'line' : 'lines'}`
}

function stationSegmentPath(startAngle: number, endAngle: number, fillColor: string): string {
  const radius = 8.4
  const center = 13
  const startPoint = polarPoint(center, center, radius, startAngle)
  const endPoint = polarPoint(center, center, radius, endAngle)
  const largeArcFlag = endAngle - startAngle > Math.PI ? 1 : 0

  return `
    <path
      d="M ${center} ${center} L ${startPoint.x} ${startPoint.y} A ${radius} ${radius} 0 ${largeArcFlag} 1 ${endPoint.x} ${endPoint.y} Z"
      fill="${fillColor}"
      stroke="#ffffff"
      stroke-width="1.1"
      stroke-linejoin="round"
    />
  `
}

function polarPoint(
  centerX: number,
  centerY: number,
  radius: number,
  angleInRadians: number
): { x: string; y: string } {
  return {
    x: (centerX + radius * Math.cos(angleInRadians)).toFixed(3),
    y: (centerY + radius * Math.sin(angleInRadians)).toFixed(3)
  }
}
