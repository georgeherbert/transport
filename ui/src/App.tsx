import {
  type CSSProperties,
  type ReactNode,
  memo,
  startTransition,
  useDeferredValue,
  useEffect,
  useEffectEvent,
  useRef,
  useState
} from 'react'
import L, { type DivIcon, type LeafletMouseEvent, type Map as LeafletMap, type Point } from 'leaflet'
import { MapContainer, Marker, Pane, Polyline, Popup, TileLayer, useMap, useMapEvents } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'
import { reconcileServices, reconcileStations } from './snapshotReconciler'
import {
  railMapDynamicStateFromUnknown,
  railMapSnapshotFromUnknown,
  transportErrorMessageFromUnknown
} from './transportApi'
import type {
  Coordinate,
  FeaturePickerFeature,
  FeaturePickerState,
  FutureStationArrival,
  PlottedRailService,
  RailLine,
  RailMapDynamicState,
  RailMapSnapshot,
  RailService,
  RailStation,
  SelectedLineId,
  SelectedMapFeature,
  StationArrival
} from './types'

type MapStatus = 'error' | 'live' | 'loading' | 'refreshing' | 'stale'
type AccentStyle = CSSProperties & { '--accent-color': string }

interface LineOption {
  id: string
  name: string
}

interface VisibleLinePath {
  id: string
  lineId: string
  coordinates: Coordinate[]
}

interface PopupCardProps {
  title: string
  accentColor?: string | null
  kicker?: string | null
  children: ReactNode
}

interface PopupRowProps {
  label: string
  value: string
}

interface LineBadgesProps {
  lineIds: string[]
}

interface StatusItemProps {
  label: string
  value: number | string
}

interface MapZoomStateProps {
  onZoomStart: () => void
  onZoomEnd: () => void
}

interface ServiceMarkerProps {
  service: PlottedRailService
  isSelected: boolean
  onSelect: (feature: SelectedMapFeature, event: LeafletMouseEvent, map: LeafletMap) => void
  onDeselect: () => void
}

interface StationMarkerProps {
  station: RailStation
  selectedLineId: SelectedLineId
  isSelected: boolean
  onSelect: (feature: SelectedMapFeature, event: LeafletMouseEvent, map: LeafletMap) => void
  onDeselect: () => void
}

interface StaticMapLayersProps {
  mapSnapshot: RailMapSnapshot | null
  selectedLineId: SelectedLineId
  visibleStations: RailStation[]
  selectedStationId: string | null
  onStationClick: (feature: SelectedMapFeature, event: LeafletMouseEvent, map: LeafletMap) => void
  onDeselectStation: (stationId: string) => void
}

interface OverlapFeaturePickerProps {
  featurePicker: FeaturePickerState
  onChoose: (feature: FeaturePickerFeature) => void
  onDismiss: () => void
}

interface ClickableFeature extends FeaturePickerFeature {
  point: Point
}

const londonCenter: [number, number] = [51.5072, -0.1276]
const basemapUrl = 'https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png'
const basemapAttribution =
  '&copy; <a href="https://carto.com/attributions" target="_blank" rel="noreferrer">CARTO</a> ' +
  '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">OpenStreetMap</a>'

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
const featurePickerPopupOffset: [number, number] = [0, -12]

function App() {
  const [mapSnapshot, setMapSnapshot] = useState<RailMapSnapshot | null>(null)
  const [status, setStatus] = useState<MapStatus>('loading')
  const [errorMessage, setErrorMessage] = useState('')
  const [selectedLineId, setSelectedLineId] = useState<SelectedLineId>('all')
  const [selectedMapFeature, setSelectedMapFeature] = useState<SelectedMapFeature | null>(null)
  const [featurePicker, setFeaturePicker] = useState<FeaturePickerState | null>(null)
  const pendingSnapshotRef = useRef<RailMapSnapshot | null>(null)
  const pendingServicePositionsRef = useRef<RailMapDynamicState | null>(null)
  const servicePositionsAnimationFrameRef = useRef<number | null>(null)
  const isZoomingRef = useRef(false)
  const plottedServicesRef = useRef<PlottedRailService[]>([])
  const visibleStationsRef = useRef<RailStation[]>([])

  const clearQueuedServicePositions = useEffectEvent(() => {
    pendingServicePositionsRef.current = null

    if (servicePositionsAnimationFrameRef.current == null) {
      return
    }

    window.cancelAnimationFrame(servicePositionsAnimationFrameRef.current)
    servicePositionsAnimationFrameRef.current = null
  })

  const clearQueuedSnapshot = useEffectEvent(() => {
    pendingSnapshotRef.current = null
  })

  const scheduleQueuedServicePositionsFlush = useEffectEvent(() => {
    if (pendingServicePositionsRef.current == null || servicePositionsAnimationFrameRef.current != null) {
      return
    }

    servicePositionsAnimationFrameRef.current = window.requestAnimationFrame(() => {
      servicePositionsAnimationFrameRef.current = null
      flushQueuedServicePositions()
    })
  })

  const applySnapshotUpdate = useEffectEvent((snapshot: RailMapSnapshot) => {
    if (isZoomingRef.current) {
      pendingSnapshotRef.current = snapshot
      return
    }

    clearQueuedSnapshot()
    clearQueuedServicePositions()

    startTransition(() => {
      setMapSnapshot(snapshot)
      setErrorMessage('')
      setStatus('live')
    })
  })

  const applyErrorUpdate = useEffectEvent((message: string) => {
    clearQueuedSnapshot()
    clearQueuedServicePositions()

    startTransition(() => {
      setErrorMessage(message)
      setStatus(mapSnapshot == null ? 'error' : 'stale')
    })
  })

  const flushQueuedServicePositions = useEffectEvent(() => {
    const servicePositions = pendingServicePositionsRef.current
    pendingServicePositionsRef.current = null

    if (servicePositions == null) {
      return
    }

    startTransition(() => {
      setMapSnapshot(currentSnapshot => {
        if (currentSnapshot == null) {
          return currentSnapshot
        }

        const stations = reconcileStations(currentSnapshot.stations, servicePositions.stations)
        const services = reconcileServices(currentSnapshot.services, servicePositions.services)

        return {
          ...currentSnapshot,
          generatedAt: servicePositions.generatedAt,
          stationsFailed: servicePositions.stationsFailed,
          partial: servicePositions.partial,
          serviceCount: servicePositions.serviceCount,
          stations,
          services
        }
      })
      setErrorMessage('')
      setStatus('live')
    })
  })

  const flushQueuedSnapshot = useEffectEvent(() => {
    const snapshot = pendingSnapshotRef.current
    pendingSnapshotRef.current = null

    if (snapshot == null) {
      return false
    }

    startTransition(() => {
      setMapSnapshot(snapshot)
      setErrorMessage('')
      setStatus('live')
    })

    return true
  })

  const queueServicePositionsUpdate = useEffectEvent((servicePositions: RailMapDynamicState) => {
    pendingServicePositionsRef.current = servicePositions

    if (isZoomingRef.current) {
      return
    }

    scheduleQueuedServicePositionsFlush()
  })

  const pauseLiveMapUpdates = useEffectEvent(() => {
    isZoomingRef.current = true

    if (servicePositionsAnimationFrameRef.current == null) {
      return
    }

    window.cancelAnimationFrame(servicePositionsAnimationFrameRef.current)
    servicePositionsAnimationFrameRef.current = null
  })

  const resumeLiveMapUpdates = useEffectEvent(() => {
    isZoomingRef.current = false

    if (flushQueuedSnapshot()) {
      window.requestAnimationFrame(() => {
        scheduleQueuedServicePositionsFlush()
      })
    } else {
      scheduleQueuedServicePositionsFlush()
    }
  })

  const applyStreamDisconnect = useEffectEvent(() => {
    startTransition(() => {
      setStatus(mapSnapshot == null ? 'error' : 'stale')
    })
  })

  const requestCachedMap = useEffectEvent(async () => {
    try {
      const response = await fetch('/api/rail/map', {
        headers: {
          Accept: 'application/json'
        }
      })
      const payload: unknown = await response.json()

      if (!response.ok) {
        throw new Error(
          transportErrorMessageFromUnknown(payload, 'Unable to load the projected rail map.')
        )
      }

      applySnapshotUpdate(railMapSnapshotFromUnknown(payload))
    } catch (error) {
      const message =
        error instanceof Error ? error.message : 'Unable to load the projected rail map.'

      applyErrorUpdate(message)
    }
  })

  useEffect(() => {
    requestCachedMap()
    const eventSource = new EventSource('/api/rail/map/stream')

    eventSource.addEventListener('snapshot', (event: MessageEvent<string>) => {
      applySnapshotUpdate(railMapSnapshotFromUnknown(JSON.parse(event.data)))
    })

    eventSource.addEventListener('service_positions', (event: MessageEvent<string>) => {
      queueServicePositionsUpdate(railMapDynamicStateFromUnknown(JSON.parse(event.data)))
    })

    eventSource.addEventListener('transport_error', (event: MessageEvent<string>) => {
      const payload = JSON.parse(event.data)
      applyErrorUpdate(
        transportErrorMessageFromUnknown(payload, 'Unable to load the projected rail map.')
      )
    })

    eventSource.onerror = () => {
      applyStreamDisconnect()
    }

    return () => {
      clearQueuedSnapshot()
      clearQueuedServicePositions()
      eventSource.close()
    }
  }, [])

  const deferredSelectedLineId = useDeferredValue(selectedLineId)

  useEffect(() => {
    setSelectedMapFeature(null)
    setFeaturePicker(null)
  }, [deferredSelectedLineId])

  const lineOptions = buildLineOptions(mapSnapshot)
  const visibleServices = buildVisibleServices(mapSnapshot, deferredSelectedLineId)
  const plottedServices = visibleServices.filter(isPlottedService)
  const visibleStations = buildVisibleStations(mapSnapshot, deferredSelectedLineId)
  plottedServicesRef.current = plottedServices
  visibleStationsRef.current = visibleStations
  const selectedStationId =
    selectedMapFeature?.kind === 'station' ? selectedMapFeature.id : null
  const selectedServiceId =
    selectedMapFeature?.kind === 'service' ? selectedMapFeature.id : null
  const chooseMapFeature = (feature: FeaturePickerFeature) => {
    setFeaturePicker(null)
    setSelectedMapFeature({
      kind: feature.kind,
      id: feature.id
    })
  }
  const handleFeatureClick = (clickedFeature: SelectedMapFeature, event: LeafletMouseEvent, map: LeafletMap) => {
    event.originalEvent?.stopPropagation()

    const overlappingFeatures = overlappingFeaturesForFeature(
      map,
      clickedFeature,
      plottedServicesRef.current,
      visibleStationsRef.current
    )

    if (overlappingFeatures.length > 1) {
      setSelectedMapFeature(null)
      setFeaturePicker({
        lat: event.latlng.lat,
        lon: event.latlng.lng,
        features: prioritizedOverlapFeatures(overlappingFeatures, clickedFeature)
      })
    } else {
      setFeaturePicker(null)
      setSelectedMapFeature(clickedFeature)
    }
  }

  return (
    <div className="page">
      <header className="topbar">
        <div className="topbar-copy">
          <h1>London Rail Network Live</h1>
          <p>Live TfL service positions projected onto imported OpenStreetMap rail alignments.</p>
        </div>
        <div className="topbar-actions">
          <span className="topbar-generated">
            {mapSnapshot == null ? 'Waiting for first snapshot' : `Updated ${formatDateTime(mapSnapshot.generatedAt)}`}
          </span>
        </div>
      </header>

      {errorMessage !== '' ? <div className="banner banner--error">{errorMessage}</div> : null}
      {mapSnapshot?.partial ? (
        <div className="banner banner--warning">
          Live feed incomplete. Some upstream data was unavailable.
        </div>
      ) : null}

      <main className="main-layout">
        <section className="map-stage">
          <div className="map-controls">
            <label className="toolbar-field">
              <span>Line</span>
              <select value={selectedLineId} onChange={event => setSelectedLineId(event.target.value)}>
                <option value="all">All lines</option>
                {lineOptions.map(line => (
                  <option key={line.id} value={line.id}>
                    {line.name}
                  </option>
                ))}
              </select>
            </label>

            <div className="status-strip">
              <StatusItem label="Status" value={statusLabelFor(status)} />
              <StatusItem label="Services" value={mapSnapshot?.serviceCount ?? '...'} />
              <StatusItem label="Plotted" value={plottedServices.length} />
              <StatusItem label="Station gaps" value={mapSnapshot?.stationsFailed ?? '...'} />
            </div>
          </div>

          <section className="map-panel">
            <MapContainer
              center={londonCenter}
              zoom={11}
              minZoom={9}
              maxZoom={15}
              preferCanvas={true}
              scrollWheelZoom={true}
              className="map"
            >
              <MapZoomState
                onZoomStart={pauseLiveMapUpdates}
                onZoomEnd={resumeLiveMapUpdates}
              />

              <Pane name="rail-lines" style={{ zIndex: 350 }} />
              <Pane name="rail-stations" style={{ zIndex: 610 }} />
              <Pane name="rail-services" style={{ zIndex: 620 }} />

              <TileLayer
                attribution={basemapAttribution}
                maxZoom={20}
                url={basemapUrl}
              />

              <StaticMapLayers
                mapSnapshot={mapSnapshot}
                selectedLineId={deferredSelectedLineId}
                visibleStations={visibleStations}
                selectedStationId={selectedStationId}
                onStationClick={handleFeatureClick}
                  onDeselectStation={(stationId: string) =>
                    setSelectedMapFeature(currentFeature =>
                      currentFeature?.kind === 'station' && currentFeature.id === stationId
                        ? null
                      : currentFeature
                  )
                }
              />

              {plottedServices.map(service => (
                <ServiceMarker
                  key={service.serviceId}
                  service={service}
                  isSelected={selectedServiceId === service.serviceId}
                  onSelect={handleFeatureClick}
                  onDeselect={() =>
                    setSelectedMapFeature(currentFeature =>
                      currentFeature?.kind === 'service' && currentFeature.id === service.serviceId
                        ? null
                        : currentFeature
                    )
                  }
                />
              ))}

              {featurePicker != null ? (
                <OverlapFeaturePicker
                  featurePicker={featurePicker}
                  onChoose={chooseMapFeature}
                  onDismiss={() => setFeaturePicker(null)}
                />
              ) : null}
            </MapContainer>
          </section>
        </section>
      </main>
    </div>
  )
}

function StatusItem({ label, value }: StatusItemProps) {
  return (
    <div className="status-item">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  )
}

function MapZoomState({ onZoomStart, onZoomEnd }: MapZoomStateProps) {
  useMapEvents({
    zoomstart: onZoomStart,
    zoomend: onZoomEnd
  })

  return null
}

function PopupCard({ title, accentColor, kicker, children }: PopupCardProps) {
  return (
    <div className="map-popup">
      <div className="map-popup-header">
        {accentColor != null ? (
          <span
            className="map-popup-accent"
            style={accentStyle(accentColor)}
          ></span>
        ) : null}
        <div className="map-popup-heading">
          {kicker != null ? <span className="map-popup-kicker">{kicker}</span> : null}
          <strong className="map-popup-title">{title}</strong>
        </div>
      </div>
      <div className="map-popup-body">{children}</div>
    </div>
  )
}

function PopupRow({ label, value }: PopupRowProps) {
  return (
    <div className="map-popup-row">
      <span className="map-popup-label">{label}</span>
      <span className="map-popup-value">{value}</span>
    </div>
  )
}

function LineBadges({ lineIds }: LineBadgesProps) {
  const orderedLineIds = sortedLineIds(lineIds)

  return (
    <div className="map-popup-badges">
      {orderedLineIds.map(lineId => (
        <span className="map-popup-badge" key={lineId}>
          <span
            className="map-popup-badge-swatch"
            style={accentStyle(colorForLine(lineId))}
          ></span>
          {prettifyLineId(lineId)}
        </span>
      ))}
    </div>
  )
}

const ServiceMarker = memo(
  function ServiceMarker({ service, isSelected, onSelect, onDeselect }: ServiceMarkerProps) {
    const markerRef = useRef<L.Marker | null>(null)
    const map = useMap()

    useEffect(() => {
      if (!isSelected) {
        return
      }

      markerRef.current?.openPopup()
    }, [isSelected])

    useEffect(() => {
      const marker = markerRef.current

      if (marker == null) {
        return
      }

      const handleClick = (event: LeafletMouseEvent) => {
        onSelect(
          {
            kind: 'service',
            id: service.serviceId
          },
          event,
          map
        )
      }

      marker.on('click', handleClick)
      marker.on('popupclose', onDeselect)

      return () => {
        marker.off('click', handleClick)
        marker.off('popupclose', onDeselect)
      }
    }, [map, onDeselect, onSelect, service.serviceId])

    return (
      <Marker
        ref={markerRef}
        position={[service.coordinate.lat, service.coordinate.lon]}
        icon={createServiceIcon(service)}
        pane="rail-services"
      >
        {isSelected ? (
          <Popup>
            <PopupCard
              title={service.lineName}
              accentColor={colorForLine(service.lineId)}
              kicker="Service"
            >
              <PopupRow label="Current Location" value={currentLocationLabelForService(service)} />
              <PopupRow label="Destination" value={destinationLabelForService(service)} />
              {service.towards != null ? (
                <PopupRow label="Towards" value={service.towards} />
              ) : null}
              {service.futureArrivals != null && service.futureArrivals.length > 0 ? (
                <div className="map-popup-section">
                  <span className="map-popup-label">Arrivals</span>
                  <div className="map-popup-arrivals">
                    {service.futureArrivals.map(arrival => (
                      <div
                        className="map-popup-arrival-row"
                        key={`${arrival.stationId ?? arrival.stationName}:${arrival.expectedArrival}`}
                      >
                        <span className="map-popup-arrival-station">{arrival.stationName}</span>
                        <span className="map-popup-arrival-time">
                          {formatClockTime(arrival.expectedArrival)}
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              ) : null}
            </PopupCard>
          </Popup>
        ) : null}
      </Marker>
    )
  },
  areServiceMarkerPropsEqual
)

const StationMarker = memo(
  function StationMarker({ station, selectedLineId, isSelected, onSelect, onDeselect }: StationMarkerProps) {
    const markerRef = useRef<L.Marker | null>(null)
    const map = useMap()

    useEffect(() => {
      if (!isSelected) {
        return
      }

      markerRef.current?.openPopup()
    }, [isSelected])

    useEffect(() => {
      const marker = markerRef.current

      if (marker == null) {
        return
      }

      const handleClick = (event: LeafletMouseEvent) => {
        onSelect(
          {
            kind: 'station',
            id: station.id
          },
          event,
          map
        )
      }

      marker.on('click', handleClick)
      marker.on('popupclose', onDeselect)

      return () => {
        marker.off('click', handleClick)
        marker.off('popupclose', onDeselect)
      }
    }, [map, onDeselect, onSelect, station.id])

    return (
      <Marker
        ref={markerRef}
        position={[station.coordinate.lat, station.coordinate.lon]}
        icon={createStationIcon(station, selectedLineId)}
        pane="rail-stations"
      >
        {isSelected ? (
          <Popup>
            <PopupCard title={station.name} kicker="Station">
              <div className="map-popup-section">
                <span className="map-popup-label">Lines</span>
                <LineBadges lineIds={station.lineIds} />
              </div>
              {station.arrivals != null && station.arrivals.length > 0 ? (
                <div className="map-popup-section">
                  <span className="map-popup-label">Arrivals</span>
                  <div className="map-popup-arrivals">
                    {station.arrivals.map(arrival => (
                      <div
                        className="map-popup-arrival-row"
                        key={`${arrival.serviceId}:${arrival.expectedArrival}`}
                      >
                        <span className="map-popup-arrival-service">
                          <span
                            className="map-popup-arrival-line"
                            style={accentStyle(colorForLine(arrival.lineId))}
                          ></span>
                          <span className="map-popup-arrival-station">
                            {stationArrivalLabelFor(arrival)}
                          </span>
                        </span>
                        <span className="map-popup-arrival-time">
                          {formatClockTime(arrival.expectedArrival)}
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              ) : null}
            </PopupCard>
          </Popup>
        ) : null}
      </Marker>
    )
  },
  areStationMarkerPropsEqual
)

const StaticMapLayers = memo(
  function StaticMapLayers({
    mapSnapshot,
    selectedLineId,
    visibleStations,
    selectedStationId,
    onStationClick,
    onDeselectStation
  }: StaticMapLayersProps) {
    const visibleLinePaths = buildVisibleLinePaths(mapSnapshot, selectedLineId)

    return (
      <>
        {visibleLinePaths.map(path => (
          <Polyline
            key={path.id}
            pane="rail-lines"
            positions={path.coordinates.map(leafletPosition)}
            color={colorForLine(path.lineId)}
            weight={4}
            opacity={0.65}
          />
        ))}

        {visibleStations.map(station => (
          <StationMarker
            key={station.id}
            station={station}
            selectedLineId={selectedLineId}
            isSelected={selectedStationId === station.id}
            onSelect={onStationClick}
            onDeselect={() => onDeselectStation(station.id)}
          />
        ))}
      </>
    )
  },
  areStaticMapLayersEqual
)

const OverlapFeaturePicker = memo(
  function OverlapFeaturePicker({ featurePicker, onChoose, onDismiss }: OverlapFeaturePickerProps) {
    const popupRef = useRef<L.Popup | null>(null)

    useEffect(() => {
      const popup = popupRef.current

      if (popup == null) {
        return
      }

      popup.on('remove', onDismiss)

      return () => {
        popup.off('remove', onDismiss)
      }
    }, [onDismiss])

    return (
      <Popup
        ref={popupRef}
        offset={featurePickerPopupOffset}
        position={[featurePicker.lat, featurePicker.lon]}
      >
        <PopupCard title="Select">
          <div className="map-popup-options">
            {featurePicker.features.map(feature => (
              <button
                className="map-popup-option"
                key={`${feature.kind}:${feature.id}`}
                onClick={clickEvent => {
                  clickEvent.preventDefault()
                  clickEvent.stopPropagation()
                  onChoose(feature)
                }}
                onMouseDown={mouseEvent => {
                  mouseEvent.preventDefault()
                  mouseEvent.stopPropagation()
                }}
                type="button"
              >
                <span
                  className={`map-popup-option-icon map-popup-option-icon--${feature.kind}`}
                  style={feature.accentColor == null ? undefined : accentStyle(feature.accentColor)}
                ></span>
                <span className="map-popup-option-copy">
                  <span className="map-popup-option-title">{feature.title}</span>
                  <span className="map-popup-option-detail">{feature.detail}</span>
                </span>
              </button>
            ))}
          </div>
        </PopupCard>
      </Popup>
    )
  },
  areOverlapFeaturePickerPropsEqual
)

function buildLineOptions(mapSnapshot: RailMapSnapshot | null): LineOption[] {
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

function buildVisibleLinePaths(mapSnapshot: RailMapSnapshot | null, selectedLineId: SelectedLineId): VisibleLinePath[] {
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

function buildVisibleServices(mapSnapshot: RailMapSnapshot | null, selectedLineId: SelectedLineId): RailService[] {
  if (mapSnapshot == null) {
    return []
  }

  return mapSnapshot.services
    .filter(service => selectedLineId === 'all' || service.lineId === selectedLineId)
}

function buildVisibleStations(mapSnapshot: RailMapSnapshot | null, selectedLineId: SelectedLineId): RailStation[] {
  if (mapSnapshot == null) {
    return []
  }

  return mapSnapshot.stations
    .filter(station => selectedLineId === 'all' || station.lineIds.includes(selectedLineId))
    .sort((leftStation, rightStation) => leftStation.name.localeCompare(rightStation.name))
}

function overlappingFeaturesForFeature(
  map: LeafletMap,
  clickedFeature: SelectedMapFeature,
  plottedServices: PlottedRailService[],
  visibleStations: RailStation[]
): FeaturePickerFeature[] {
  const serviceFeatures: ClickableFeature[] = plottedServices.map(service => ({
    kind: 'service',
    id: service.serviceId,
    title: `${service.lineName} service`,
    detail: destinationLabelForService(service),
    accentColor: colorForLine(service.lineId),
    point: map.latLngToContainerPoint(leafletPosition(service.coordinate))
  }))
  const stationFeatures: ClickableFeature[] = visibleStations.map(station => ({
    kind: 'station',
    id: station.id,
    title: station.name,
    detail: stationDetailLabelForPicker(station),
    accentColor: null,
    point: map.latLngToContainerPoint(leafletPosition(station.coordinate))
  }))
  const clickableFeatures: ClickableFeature[] = [...serviceFeatures, ...stationFeatures]
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

function featuresOverlap(leftFeature: ClickableFeature, rightFeature: ClickableFeature): boolean {
  const overlapRadiusPixels = overlapRadiusFor(leftFeature.kind, rightFeature.kind)

  return pointDistance(leftFeature.point, rightFeature.point) <= overlapRadiusPixels
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

function prioritizedOverlapFeatures(features: FeaturePickerFeature[], clickedFeature: SelectedMapFeature): FeaturePickerFeature[] {
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

function pointDistance(leftPoint: Point, rightPoint: Point): number {
  const deltaX = leftPoint.x - rightPoint.x
  const deltaY = leftPoint.y - rightPoint.y

  return Math.sqrt((deltaX * deltaX) + (deltaY * deltaY))
}

function areStaticMapLayersEqual(previousProps: StaticMapLayersProps, nextProps: StaticMapLayersProps): boolean {
  return (
    previousProps.selectedLineId === nextProps.selectedLineId &&
    previousProps.selectedStationId === nextProps.selectedStationId &&
    previousProps.mapSnapshot?.lines === nextProps.mapSnapshot?.lines &&
    previousProps.mapSnapshot?.stations === nextProps.mapSnapshot?.stations
  )
}

function areServiceMarkerPropsEqual(previousProps: ServiceMarkerProps, nextProps: ServiceMarkerProps): boolean {
  return (
    previousProps.isSelected === nextProps.isSelected &&
    previousProps.service === nextProps.service
  )
}

function areStationMarkerPropsEqual(previousProps: StationMarkerProps, nextProps: StationMarkerProps): boolean {
  return (
    previousProps.selectedLineId === nextProps.selectedLineId &&
    previousProps.isSelected === nextProps.isSelected &&
    previousProps.station === nextProps.station
  )
}

function areOverlapFeaturePickerPropsEqual(
  previousProps: OverlapFeaturePickerProps,
  nextProps: OverlapFeaturePickerProps
): boolean {
  return previousProps.featurePicker === nextProps.featurePicker
}

function statusLabelFor(status: MapStatus): string {
  if (status === 'loading') {
    return 'Loading'
  }

  if (status === 'refreshing') {
    return 'Refreshing'
  }

  if (status === 'stale') {
    return 'Stale'
  }

  if (status === 'error') {
    return 'Unavailable'
  }

  return 'Live'
}

function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    day: '2-digit',
    month: 'short'
  }).format(new Date(value))
}

function formatClockTime(value: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  }).format(new Date(value))
}

function currentLocationLabelForService(service: RailService): string {
  return service.currentLocation
}

function destinationLabelForService(service: RailService): string {
  return service.destinationName ?? 'Destination unavailable'
}

function stationDetailLabelForPicker(station: RailStation): string {
  return `${station.lineIds.length} ${station.lineIds.length === 1 ? 'line' : 'lines'}`
}

function stationArrivalLabelFor(arrival: StationArrival): string {
  return arrival.destinationName ?? 'Destination unavailable'
}

function prettifyLineId(lineId: string): string {
  return lineId
    .split('-')
    .map(word => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
}

function colorForLine(lineId: string): string {
  return linePalette[lineId] ?? '#1f6feb'
}

function sortedLineIds(lineIds: string[]): string[] {
  return [...lineIds].sort((leftLineId, rightLineId) =>
    prettifyLineId(leftLineId).localeCompare(prettifyLineId(rightLineId))
  )
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

function createServiceIcon(service: PlottedRailService): DivIcon {
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
  const cacheKey = iconCacheKeyForService(service.lineId, headingDegrees, service.headingDegrees == null)
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

function createStationIcon(station: RailStation, selectedLineId: SelectedLineId): DivIcon {
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

function segmentedStationDot(lineIds: string[]): string {
  return lineIds
    .map((lineId, index) => {
      const startAngle = (-Math.PI / 2) + ((Math.PI * 2 * index) / lineIds.length)
      const endAngle = (-Math.PI / 2) + ((Math.PI * 2 * (index + 1)) / lineIds.length)

      return stationSegmentPath(startAngle, endAngle, colorForLine(lineId))
    })
    .join('')
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

function iconCacheKeyForService(lineId: string, headingDegrees: number, headingHidden: boolean): string {
  return `${lineId}:${headingHidden ? 'hidden' : headingDegrees}`
}

function roundedHeadingForIcon(headingDegrees: number | null): number {
  if (headingDegrees == null) {
    return 0
  }

  return Math.round(headingDegrees)
}

function accentStyle(accentColor: string): AccentStyle {
  return {
    '--accent-color': accentColor
  }
}

function isPlottedService(service: RailService): service is PlottedRailService {
  return service.coordinate != null
}

function leafletPosition(coordinate: Coordinate): [number, number] {
  return [coordinate.lat, coordinate.lon]
}

export default App
