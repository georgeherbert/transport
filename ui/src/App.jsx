import {
  memo,
  startTransition,
  useDeferredValue,
  useEffect,
  useEffectEvent,
  useRef,
  useState
} from 'react'
import L from 'leaflet'
import { MapContainer, Marker, Pane, Polyline, Popup, TileLayer, useMap } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'

const londonCenter = [51.5072, -0.1276]
const basemapUrl = 'https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png'
const basemapAttribution =
  '&copy; <a href="https://carto.com/attributions" target="_blank" rel="noreferrer">CARTO</a> ' +
  '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">OpenStreetMap</a>'

const linePalette = {
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

const serviceIconCache = new Map()
const stationIconCache = new Map()
const serviceHitRadiusPixels = 18
const stationHitRadiusPixels = 10

function App() {
  const [mapSnapshot, setMapSnapshot] = useState(null)
  const [status, setStatus] = useState('loading')
  const [errorMessage, setErrorMessage] = useState('')
  const [selectedLineId, setSelectedLineId] = useState('all')
  const [selectedMapFeature, setSelectedMapFeature] = useState(null)
  const [featurePicker, setFeaturePicker] = useState(null)
  const pendingServicePositionsRef = useRef(null)
  const servicePositionsAnimationFrameRef = useRef(null)

  const clearQueuedServicePositions = useEffectEvent(() => {
    pendingServicePositionsRef.current = null

    if (servicePositionsAnimationFrameRef.current == null) {
      return
    }

    window.cancelAnimationFrame(servicePositionsAnimationFrameRef.current)
    servicePositionsAnimationFrameRef.current = null
  })

  const applySnapshotUpdate = useEffectEvent(snapshot => {
    clearQueuedServicePositions()

    startTransition(() => {
      setMapSnapshot(snapshot)
      setErrorMessage('')
      setStatus('live')
    })
  })

  const applyErrorUpdate = useEffectEvent(message => {
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

        return {
          ...currentSnapshot,
          source: servicePositions.source,
          generatedAt: servicePositions.generatedAt,
          cached: servicePositions.cached,
          cacheAgeSeconds: servicePositions.cacheAgeSeconds,
          stationsQueried: servicePositions.stationsQueried,
          stationsFailed: servicePositions.stationsFailed,
          partial: servicePositions.partial,
          serviceCount: servicePositions.serviceCount,
          stations: servicePositions.stations,
          services: servicePositions.services
        }
      })
      setErrorMessage('')
      setStatus('live')
    })
  })

  const queueServicePositionsUpdate = useEffectEvent(servicePositions => {
    pendingServicePositionsRef.current = servicePositions

    if (servicePositionsAnimationFrameRef.current != null) {
      return
    }

    servicePositionsAnimationFrameRef.current = window.requestAnimationFrame(() => {
      servicePositionsAnimationFrameRef.current = null
      flushQueuedServicePositions()
    })
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
      const payload = await response.json()

      if (!response.ok) {
        throw new Error(payload.message ?? 'Unable to load the projected rail map.')
      }

      applySnapshotUpdate(payload)
    } catch (error) {
      const message =
        error instanceof Error ? error.message : 'Unable to load the projected rail map.'

      applyErrorUpdate(message)
    }
  })

  useEffect(() => {
    requestCachedMap()
    const eventSource = new EventSource('/api/rail/map/stream')

    eventSource.addEventListener('snapshot', event => {
      applySnapshotUpdate(JSON.parse(event.data))
    })

    eventSource.addEventListener('service_positions', event => {
      queueServicePositionsUpdate(JSON.parse(event.data))
    })

    eventSource.addEventListener('transport_error', event => {
      const payload = JSON.parse(event.data)
      applyErrorUpdate(payload.message ?? 'Unable to load the projected rail map.')
    })

    eventSource.onerror = () => {
      applyStreamDisconnect()
    }

    return () => {
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
  const plottedServices = visibleServices.filter(service => service.coordinate != null)
  const visibleStations = buildVisibleStations(mapSnapshot, deferredSelectedLineId)
  const selectedStationId =
    selectedMapFeature?.kind === 'station' ? selectedMapFeature.id : null
  const selectedServiceId =
    selectedMapFeature?.kind === 'service' ? selectedMapFeature.id : null
  const chooseMapFeature = feature => {
    setFeaturePicker(null)
    setSelectedMapFeature({
      kind: feature.kind,
      id: feature.id
    })
  }
  const handleFeatureClick = (clickedFeature, event, map) => {
    event.originalEvent?.stopPropagation()

    const overlappingFeatures = overlappingFeaturesAtClick(
      map,
      event.containerPoint,
      plottedServices,
      visibleStations
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
                onDeselectStation={stationId =>
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

function StatusItem({ label, value }) {
  return (
    <div className="status-item">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  )
}

function PopupCard({ title, accentColor, kicker, children }) {
  return (
    <div className="map-popup">
      <div className="map-popup-header">
        {accentColor != null ? (
          <span
            className="map-popup-accent"
            style={{ '--accent-color': accentColor }}
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

function PopupRow({ label, value }) {
  return (
    <div className="map-popup-row">
      <span className="map-popup-label">{label}</span>
      <span className="map-popup-value">{value}</span>
    </div>
  )
}

function LineBadges({ lineIds }) {
  const orderedLineIds = sortedLineIds(lineIds)

  return (
    <div className="map-popup-badges">
      {orderedLineIds.map(lineId => (
        <span className="map-popup-badge" key={lineId}>
          <span
            className="map-popup-badge-swatch"
            style={{ '--accent-color': colorForLine(lineId) }}
          ></span>
          {prettifyLineId(lineId)}
        </span>
      ))}
    </div>
  )
}

const ServiceMarker = memo(
  function ServiceMarker({ service, isSelected, onSelect, onDeselect }) {
    const markerRef = useRef(null)
    const map = useMap()

    useEffect(() => {
      if (!isSelected) {
        return
      }

      markerRef.current?.openPopup()
    }, [isSelected])

    return (
      <Marker
        ref={markerRef}
        position={[service.coordinate.lat, service.coordinate.lon]}
        icon={createServiceIcon(service)}
        pane="rail-services"
        eventHandlers={{
          click: event =>
            onSelect(
              {
                kind: 'service',
                id: service.serviceId
              },
              event,
              map
            ),
          popupclose: onDeselect
        }}
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
  function StationMarker({ station, selectedLineId, isSelected, onSelect, onDeselect }) {
    const markerRef = useRef(null)
    const map = useMap()

    useEffect(() => {
      if (!isSelected) {
        return
      }

      markerRef.current?.openPopup()
    }, [isSelected])

    return (
      <Marker
        ref={markerRef}
        position={[station.coordinate.lat, station.coordinate.lon]}
        icon={createStationIcon(station, selectedLineId)}
        pane="rail-stations"
        eventHandlers={{
          click: event =>
            onSelect(
              {
                kind: 'station',
                id: station.id
              },
              event,
              map
            ),
          popupclose: onDeselect
        }}
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
                            style={{ '--accent-color': colorForLine(arrival.lineId) }}
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
  }) {
    const visibleLinePaths = buildVisibleLinePaths(mapSnapshot, selectedLineId)

    return (
      <>
        {visibleLinePaths.map(path => (
          <Polyline
            key={path.id}
            pane="rail-lines"
            positions={path.coordinates.map(coordinate => [coordinate.lat, coordinate.lon])}
            pathOptions={{
              color: colorForLine(path.lineId),
              weight: 4,
              opacity: 0.65
            }}
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
  function OverlapFeaturePicker({ featurePicker, onChoose, onDismiss }) {
    return (
      <Popup
        position={[featurePicker.lat, featurePicker.lon]}
        eventHandlers={{
          remove: onDismiss
        }}
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
                  style={feature.accentColor == null ? undefined : { '--accent-color': feature.accentColor }}
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

function buildLineOptions(mapSnapshot) {
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

function buildVisibleLinePaths(mapSnapshot, selectedLineId) {
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

function buildVisibleServices(mapSnapshot, selectedLineId) {
  if (mapSnapshot == null) {
    return []
  }

  return mapSnapshot.services
    .filter(service => selectedLineId === 'all' || service.lineId === selectedLineId)
}

function buildVisibleStations(mapSnapshot, selectedLineId) {
  if (mapSnapshot == null) {
    return []
  }

  return mapSnapshot.stations
    .filter(station => selectedLineId === 'all' || station.lineIds.includes(selectedLineId))
    .sort((leftStation, rightStation) => leftStation.name.localeCompare(rightStation.name))
}

function overlappingFeaturesAtClick(map, containerPoint, plottedServices, visibleStations) {
  const serviceMatches = plottedServices
    .filter(service =>
      withinHitRadius(
        containerPoint,
        map.latLngToContainerPoint([service.coordinate.lat, service.coordinate.lon]),
        serviceHitRadiusPixels
      )
    )
    .map(service => ({
      kind: 'service',
      id: service.serviceId,
      title: `${service.lineName} service`,
      detail: destinationLabelForService(service),
      accentColor: colorForLine(service.lineId)
    }))
  const stationMatches = visibleStations
    .filter(station =>
      withinHitRadius(
        containerPoint,
        map.latLngToContainerPoint([station.coordinate.lat, station.coordinate.lon]),
        stationHitRadiusPixels
      )
    )
    .map(station => ({
      kind: 'station',
      id: station.id,
      title: station.name,
      detail: stationDetailLabelForPicker(station),
      accentColor: null
    }))

  return [...serviceMatches, ...stationMatches]
}

function prioritizedOverlapFeatures(features, clickedFeature) {
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

function withinHitRadius(clickedPoint, featurePoint, hitRadiusPixels) {
  return pointDistance(clickedPoint, featurePoint) <= hitRadiusPixels
}

function pointDistance(leftPoint, rightPoint) {
  const deltaX = leftPoint.x - rightPoint.x
  const deltaY = leftPoint.y - rightPoint.y

  return Math.sqrt((deltaX * deltaX) + (deltaY * deltaY))
}

function areStaticMapLayersEqual(previousProps, nextProps) {
  return (
    previousProps.selectedLineId === nextProps.selectedLineId &&
    previousProps.selectedStationId === nextProps.selectedStationId &&
    previousProps.mapSnapshot?.lines === nextProps.mapSnapshot?.lines &&
    previousProps.mapSnapshot?.stations === nextProps.mapSnapshot?.stations
  )
}

function areServiceMarkerPropsEqual(previousProps, nextProps) {
  const previousService = previousProps.service
  const nextService = nextProps.service

  return (
    previousProps.isSelected === nextProps.isSelected &&
    previousService.serviceId === nextService.serviceId &&
    previousService.lineId === nextService.lineId &&
    previousService.lineName === nextService.lineName &&
    previousService.currentLocation === nextService.currentLocation &&
    previousService.destinationName === nextService.destinationName &&
    previousService.towards === nextService.towards &&
    previousService.coordinate?.lat === nextService.coordinate?.lat &&
    previousService.coordinate?.lon === nextService.coordinate?.lon &&
    previousService.headingDegrees === nextService.headingDegrees &&
    futureArrivalsSignature(previousService.futureArrivals) === futureArrivalsSignature(nextService.futureArrivals)
  )
}

function areStationMarkerPropsEqual(previousProps, nextProps) {
  const previousStation = previousProps.station
  const nextStation = nextProps.station

  return (
    previousProps.selectedLineId === nextProps.selectedLineId &&
    previousProps.isSelected === nextProps.isSelected &&
    previousStation.id === nextStation.id &&
    previousStation.name === nextStation.name &&
    previousStation.coordinate.lat === nextStation.coordinate.lat &&
    previousStation.coordinate.lon === nextStation.coordinate.lon &&
    previousStation.lineIds.join('|') === nextStation.lineIds.join('|') &&
    stationArrivalsSignature(previousStation.arrivals) === stationArrivalsSignature(nextStation.arrivals)
  )
}

function areOverlapFeaturePickerPropsEqual(previousProps, nextProps) {
  return previousProps.featurePicker === nextProps.featurePicker
}

function statusLabelFor(status) {
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

function formatDateTime(value) {
  return new Intl.DateTimeFormat('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    day: '2-digit',
    month: 'short'
  }).format(new Date(value))
}

function formatClockTime(value) {
  return new Intl.DateTimeFormat('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  }).format(new Date(value))
}

function currentLocationLabelForService(service) {
  return service.currentLocation
}

function destinationLabelForService(service) {
  return service.destinationName ?? 'Destination unavailable'
}

function stationDetailLabelForPicker(station) {
  return `${station.lineIds.length} ${station.lineIds.length === 1 ? 'line' : 'lines'}`
}

function stationArrivalLabelFor(arrival) {
  return arrival.destinationName ?? 'Destination unavailable'
}

function prettifyLineId(lineId) {
  return lineId
    .split('-')
    .map(word => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
}

function colorForLine(lineId) {
  return linePalette[lineId] ?? '#1f6feb'
}

function sortedLineIds(lineIds) {
  return [...lineIds].sort((leftLineId, rightLineId) =>
    prettifyLineId(leftLineId).localeCompare(prettifyLineId(rightLineId))
  )
}

function futureArrivalsSignature(futureArrivals) {
  return (futureArrivals ?? [])
    .map(arrival => `${arrival.stationId ?? arrival.stationName}:${arrival.expectedArrival}`)
    .join('|')
}

function stationArrivalsSignature(arrivals) {
  return (arrivals ?? [])
    .map(arrival => `${arrival.serviceId}:${arrival.expectedArrival}`)
    .join('|')
}

function markerLabelForLine(lineId) {
  const words = prettifyLineId(lineId).split(' ')

  if (words.length === 1) {
    return words[0].slice(0, 2).toUpperCase()
  }

  return words.map(word => word.charAt(0)).join('').slice(0, 2).toUpperCase()
}

function markerTextColorForLine(lineId) {
  const [red, green, blue] = rgbComponentsForHex(colorForLine(lineId))
  const luminance = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255

  if (luminance > 0.62) {
    return '#14213d'
  }

  return '#ffffff'
}

function rgbComponentsForHex(hexColor) {
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

function createServiceIcon(service) {
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
    popupAnchor: [0, -24]
  })

  serviceIconCache.set(cacheKey, icon)
  return icon
}

function createStationIcon(station, selectedLineId) {
  const visibleLineIds =
    selectedLineId !== 'all' && station.lineIds.includes(selectedLineId)
      ? [selectedLineId]
      : sortedLineIds(station.lineIds)
  const cacheKey = `${selectedLineId}:${visibleLineIds.join('|')}`
  const cachedIcon = stationIconCache.get(cacheKey)

  if (cachedIcon != null) {
    return cachedIcon
  }

  const shapeMarkup =
    visibleLineIds.length === 1
      ? singleLineStationDot(visibleLineIds[0])
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

function singleLineStationDot(lineId) {
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

function segmentedStationDot(lineIds) {
  return lineIds
    .map((lineId, index) => {
      const startAngle = (-Math.PI / 2) + ((Math.PI * 2 * index) / lineIds.length)
      const endAngle = (-Math.PI / 2) + ((Math.PI * 2 * (index + 1)) / lineIds.length)

      return stationSegmentPath(startAngle, endAngle, colorForLine(lineId))
    })
    .join('')
}

function stationSegmentPath(startAngle, endAngle, fillColor) {
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

function polarPoint(centerX, centerY, radius, angleInRadians) {
  return {
    x: (centerX + radius * Math.cos(angleInRadians)).toFixed(3),
    y: (centerY + radius * Math.sin(angleInRadians)).toFixed(3)
  }
}

function iconCacheKeyForService(lineId, headingDegrees, headingHidden) {
  return `${lineId}:${headingHidden ? 'hidden' : headingDegrees}`
}

function roundedHeadingForIcon(headingDegrees) {
  if (headingDegrees == null) {
    return 0
  }

  return Math.round(headingDegrees)
}

export default App
