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
import { CircleMarker, MapContainer, Marker, Polyline, Popup, TileLayer } from 'react-leaflet'
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

const trainIconCache = new Map()

function App() {
  const [mapSnapshot, setMapSnapshot] = useState(null)
  const [status, setStatus] = useState('loading')
  const [errorMessage, setErrorMessage] = useState('')
  const [selectedLineId, setSelectedLineId] = useState('all')
  const [selectedMapFeature, setSelectedMapFeature] = useState(null)
  const pendingTrainPositionsRef = useRef(null)
  const trainPositionsAnimationFrameRef = useRef(null)

  const clearQueuedTrainPositions = useEffectEvent(() => {
    pendingTrainPositionsRef.current = null

    if (trainPositionsAnimationFrameRef.current == null) {
      return
    }

    window.cancelAnimationFrame(trainPositionsAnimationFrameRef.current)
    trainPositionsAnimationFrameRef.current = null
  })

  const applySnapshotUpdate = useEffectEvent(snapshot => {
    clearQueuedTrainPositions()

    startTransition(() => {
      setMapSnapshot(snapshot)
      setErrorMessage('')
      setStatus('live')
    })
  })

  const applyErrorUpdate = useEffectEvent(message => {
    clearQueuedTrainPositions()

    startTransition(() => {
      setErrorMessage(message)
      setStatus(mapSnapshot == null ? 'error' : 'stale')
    })
  })

  const flushQueuedTrainPositions = useEffectEvent(() => {
    const trainPositions = pendingTrainPositionsRef.current
    pendingTrainPositionsRef.current = null

    if (trainPositions == null) {
      return
    }

    startTransition(() => {
      setMapSnapshot(currentSnapshot => {
        if (currentSnapshot == null) {
          return currentSnapshot
        }

        return {
          ...currentSnapshot,
          source: trainPositions.source,
          generatedAt: trainPositions.generatedAt,
          cached: trainPositions.cached,
          cacheAgeSeconds: trainPositions.cacheAgeSeconds,
          stationsQueried: trainPositions.stationsQueried,
          stationsFailed: trainPositions.stationsFailed,
          partial: trainPositions.partial,
          trainCount: trainPositions.trainCount,
          trains: trainPositions.trains
        }
      })
      setErrorMessage('')
      setStatus('live')
    })
  })

  const queueTrainPositionsUpdate = useEffectEvent(trainPositions => {
    pendingTrainPositionsRef.current = trainPositions

    if (trainPositionsAnimationFrameRef.current != null) {
      return
    }

    trainPositionsAnimationFrameRef.current = window.requestAnimationFrame(() => {
      trainPositionsAnimationFrameRef.current = null
      flushQueuedTrainPositions()
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

    eventSource.addEventListener('train_positions', event => {
      queueTrainPositionsUpdate(JSON.parse(event.data))
    })

    eventSource.addEventListener('transport_error', event => {
      const payload = JSON.parse(event.data)
      applyErrorUpdate(payload.message ?? 'Unable to load the projected rail map.')
    })

    eventSource.onerror = () => {
      applyStreamDisconnect()
    }

    return () => {
      clearQueuedTrainPositions()
      eventSource.close()
    }
  }, [])

  const deferredSelectedLineId = useDeferredValue(selectedLineId)

  useEffect(() => {
    setSelectedMapFeature(null)
  }, [deferredSelectedLineId])

  const lineOptions = buildLineOptions(mapSnapshot)
  const visibleTrains = buildVisibleTrains(mapSnapshot, deferredSelectedLineId)
  const plottedTrains = visibleTrains.filter(train => train.coordinate != null)
  const selectedStationId =
    selectedMapFeature?.kind === 'station' ? selectedMapFeature.id : null
  const selectedTrainId =
    selectedMapFeature?.kind === 'train' ? selectedMapFeature.id : null

  return (
    <div className="page">
      <header className="topbar">
        <div className="topbar-copy">
          <h1>London Rail Network Live</h1>
          <p>Live TfL train positions projected onto imported OpenStreetMap rail alignments.</p>
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
              <StatusItem label="Trains" value={mapSnapshot?.trainCount ?? '...'} />
              <StatusItem label="Plotted" value={plottedTrains.length} />
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
              <TileLayer
                attribution={basemapAttribution}
                maxZoom={20}
                url={basemapUrl}
              />

              <StaticMapLayers
                mapSnapshot={mapSnapshot}
                selectedLineId={deferredSelectedLineId}
                selectedStationId={selectedStationId}
                onSelectStation={stationId =>
                  setSelectedMapFeature({
                    kind: 'station',
                    id: stationId
                  })
                }
                onDeselectStation={stationId =>
                  setSelectedMapFeature(currentFeature =>
                    currentFeature?.kind === 'station' && currentFeature.id === stationId
                      ? null
                      : currentFeature
                  )
                }
              />

              {plottedTrains.map(train => (
                <TrainMarker
                  key={train.trainId}
                  train={train}
                  isSelected={selectedTrainId === train.trainId}
                  onSelect={() =>
                    setSelectedMapFeature({
                      kind: 'train',
                      id: train.trainId
                    })
                  }
                  onDeselect={() =>
                    setSelectedMapFeature(currentFeature =>
                      currentFeature?.kind === 'train' && currentFeature.id === train.trainId
                        ? null
                        : currentFeature
                    )
                  }
                />
              ))}
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
        <span
          className="map-popup-accent"
          style={{ '--accent-color': accentColor }}
        ></span>
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
  return (
    <div className="map-popup-badges">
      {lineIds.map(lineId => (
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

const TrainMarker = memo(
  function TrainMarker({ train, isSelected, onSelect, onDeselect }) {
    const markerRef = useRef(null)

    useEffect(() => {
      if (!isSelected) {
        return
      }

      markerRef.current?.openPopup()
    }, [isSelected])

    return (
      <Marker
        ref={markerRef}
        position={[train.coordinate.lat, train.coordinate.lon]}
        icon={createTrainIcon(train)}
        eventHandlers={{
          click: onSelect,
          popupclose: onDeselect
        }}
      >
        {isSelected ? (
          <Popup>
            <PopupCard
              title={train.lineName}
              accentColor={colorForLine(train.lineId)}
              kicker="Train"
            >
              <PopupRow label="Current Location" value={currentLocationLabelFor(train)} />
              <PopupRow label="Destination" value={destinationLabelFor(train)} />
              {train.towards != null ? (
                <PopupRow label="Towards" value={train.towards} />
              ) : null}
              {train.secondsToNextStop != null ? (
                <PopupRow label="Next stop" value={secondsLabelFor(train.secondsToNextStop)} />
              ) : null}
            </PopupCard>
          </Popup>
        ) : null}
      </Marker>
    )
  },
  areTrainMarkerPropsEqual
)

const StationMarker = memo(
  function StationMarker({ station, selectedLineId, isSelected, onSelect, onDeselect }) {
    const fillColor = stationColorFor(station, selectedLineId)
    const markerRef = useRef(null)

    useEffect(() => {
      if (!isSelected) {
        return
      }

      markerRef.current?.openPopup()
    }, [isSelected])

    return (
      <CircleMarker
        ref={markerRef}
        center={[station.coordinate.lat, station.coordinate.lon]}
        radius={stationRadiusFor(station, selectedLineId)}
        pathOptions={{
          color: '#ffffff',
          weight: 2,
          fillColor,
          fillOpacity: 1
        }}
        eventHandlers={{
          click: onSelect,
          popupclose: onDeselect
        }}
      >
        {isSelected ? (
          <Popup>
            <PopupCard title={station.name} accentColor={fillColor} kicker="Station">
              <div className="map-popup-section">
                <span className="map-popup-label">Lines</span>
                <LineBadges lineIds={station.lineIds} />
              </div>
            </PopupCard>
          </Popup>
        ) : null}
      </CircleMarker>
    )
  },
  areStationMarkerPropsEqual
)

const StaticMapLayers = memo(
  function StaticMapLayers({
    mapSnapshot,
    selectedLineId,
    selectedStationId,
    onSelectStation,
    onDeselectStation
  }) {
    const visibleLinePaths = buildVisibleLinePaths(mapSnapshot, selectedLineId)
    const visibleStations = buildVisibleStations(mapSnapshot, selectedLineId)

    return (
      <>
        {visibleLinePaths.map(path => (
          <Polyline
            key={path.id}
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
            onSelect={() => onSelectStation(station.id)}
            onDeselect={() => onDeselectStation(station.id)}
          />
        ))}
      </>
    )
  },
  areStaticMapLayersEqual
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

function buildVisibleTrains(mapSnapshot, selectedLineId) {
  if (mapSnapshot == null) {
    return []
  }

  return mapSnapshot.trains
    .filter(train => selectedLineId === 'all' || train.lineId === selectedLineId)
}

function buildVisibleStations(mapSnapshot, selectedLineId) {
  if (mapSnapshot == null) {
    return []
  }

  return mapSnapshot.stations
    .filter(station => selectedLineId === 'all' || station.lineIds.includes(selectedLineId))
    .sort((leftStation, rightStation) => leftStation.name.localeCompare(rightStation.name))
}

function areStaticMapLayersEqual(previousProps, nextProps) {
  return (
    previousProps.selectedLineId === nextProps.selectedLineId &&
    previousProps.selectedStationId === nextProps.selectedStationId &&
    previousProps.mapSnapshot?.lines === nextProps.mapSnapshot?.lines &&
    previousProps.mapSnapshot?.stations === nextProps.mapSnapshot?.stations
  )
}

function areTrainMarkerPropsEqual(previousProps, nextProps) {
  const previousTrain = previousProps.train
  const nextTrain = nextProps.train

  return (
    previousProps.isSelected === nextProps.isSelected &&
    previousTrain.trainId === nextTrain.trainId &&
    previousTrain.lineId === nextTrain.lineId &&
    previousTrain.lineName === nextTrain.lineName &&
    previousTrain.currentLocation === nextTrain.currentLocation &&
    previousTrain.destinationName === nextTrain.destinationName &&
    previousTrain.towards === nextTrain.towards &&
    previousTrain.secondsToNextStop === nextTrain.secondsToNextStop &&
    previousTrain.coordinate?.lat === nextTrain.coordinate?.lat &&
    previousTrain.coordinate?.lon === nextTrain.coordinate?.lon &&
    previousTrain.headingDegrees === nextTrain.headingDegrees
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
    previousStation.lineIds.join('|') === nextStation.lineIds.join('|')
  )
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

function secondsLabelFor(seconds) {
  if (seconds == null) {
    return 'Time to next stop unavailable'
  }

  if (seconds < 60) {
    return `${seconds}s to next stop`
  }

  return `${Math.round(seconds / 60)}m to next stop`
}

function currentLocationLabelFor(train) {
  return train.currentLocation
}

function destinationLabelFor(train) {
  return train.destinationName ?? 'Destination unavailable'
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

function stationColorFor(station, selectedLineId) {
  if (selectedLineId !== 'all' && station.lineIds.includes(selectedLineId)) {
    return colorForLine(selectedLineId)
  }

  return colorForLine(station.lineIds[0])
}

function stationRadiusFor(station, selectedLineId) {
  if (selectedLineId !== 'all' && station.lineIds.includes(selectedLineId)) {
    return 4.5
  }

  return station.lineIds.length > 1 ? 4.5 : 4
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

function createTrainIcon(train) {
  const lineColor = colorForLine(train.lineId)
  const headingDegrees = roundedHeadingForIcon(train.headingDegrees)
  const markerLabel = markerLabelForLine(train.lineId)
  const markerTextColor = markerTextColorForLine(train.lineId)
  const shapeMarkup =
    train.headingDegrees == null
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
  const cacheKey = iconCacheKeyForTrain(train.lineId, headingDegrees, train.headingDegrees == null)
  const cachedIcon = trainIconCache.get(cacheKey)

  if (cachedIcon != null) {
    return cachedIcon
  }

  const icon = L.divIcon({
    className: 'train-icon-shell',
    html: `
      <div class="train-marker">
        <svg class="train-marker-shape" viewBox="0 0 44 44" aria-hidden="true">
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

  trainIconCache.set(cacheKey, icon)
  return icon
}

function iconCacheKeyForTrain(lineId, headingDegrees, headingHidden) {
  return `${lineId}:${headingHidden ? 'hidden' : headingDegrees}`
}

function roundedHeadingForIcon(headingDegrees) {
  if (headingDegrees == null) {
    return 0
  }

  return Math.round(headingDegrees)
}

export default App
