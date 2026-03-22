import {
  startTransition,
  useDeferredValue,
  useEffect,
  useEffectEvent,
  useState
} from 'react'
import L from 'leaflet'
import { MapContainer, Marker, Polyline, Popup, TileLayer } from 'react-leaflet'
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
  dlr: '#00a4a7',
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

function App() {
  const [mapSnapshot, setMapSnapshot] = useState(null)
  const [status, setStatus] = useState('loading')
  const [errorMessage, setErrorMessage] = useState('')
  const [selectedLineId, setSelectedLineId] = useState('all')

  const applySnapshotUpdate = useEffectEvent(snapshot => {
    startTransition(() => {
      setMapSnapshot(snapshot)
      setErrorMessage('')
      setStatus('live')
    })
  })

  const applyErrorUpdate = useEffectEvent(message => {
    startTransition(() => {
      setErrorMessage(message)
      setStatus(mapSnapshot == null ? 'error' : 'stale')
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

    eventSource.addEventListener('transport_error', event => {
      const payload = JSON.parse(event.data)
      applyErrorUpdate(payload.message ?? 'Unable to load the projected rail map.')
    })

    eventSource.onerror = () => {
      applyStreamDisconnect()
    }

    return () => {
      eventSource.close()
    }
  }, [])

  const deferredSelectedLineId = useDeferredValue(selectedLineId)
  const lineOptions = buildLineOptions(mapSnapshot)
  const visibleLinePaths = buildVisibleLinePaths(mapSnapshot, deferredSelectedLineId)
  const visibleTrains = buildVisibleTrains(mapSnapshot, deferredSelectedLineId)
  const listedTrains = visibleTrains.slice(0, 8)

  return (
    <div className="page">
      <header className="topbar">
        <div className="topbar-copy">
          <h1>London Rail and Tram Map</h1>
          <p>Live TfL map projection from Tube, DLR, Elizabeth line, Overground, and Tram data.</p>
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
              <StatusItem label="Plotted" value={visibleTrains.filter(train => train.coordinate != null).length} />
              <StatusItem label="Station gaps" value={mapSnapshot?.stationsFailed ?? '...'} />
            </div>
          </div>

          <section className="map-panel">
          <MapContainer
            center={londonCenter}
            zoom={11}
            minZoom={9}
            maxZoom={15}
            scrollWheelZoom={true}
            className="map"
          >
            <TileLayer
              attribution={basemapAttribution}
              maxZoom={20}
              url={basemapUrl}
            />

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

            {visibleTrains.filter(train => train.coordinate != null).map(train => (
              <Marker
                key={train.trainId}
                position={[train.coordinate.lat, train.coordinate.lon]}
                icon={createTrainIcon(train)}
              >
                <Popup>
                  <div className="popup-card">
                    <strong>{train.lineLabel}</strong>
                    <TrainDetail label="Current">{currentLocationLabelFor(train)}</TrainDetail>
                    <TrainDetail label="Destination">{destinationLabelFor(train)}</TrainDetail>
                    {train.towards != null ? (
                      <TrainDetail label="Towards">{train.towards}</TrainDetail>
                    ) : null}
                    {train.secondsToNextStop != null ? (
                      <TrainDetail label="Next stop">{secondsLabelFor(train.secondsToNextStop)}</TrainDetail>
                    ) : null}
                  </div>
                </Popup>
              </Marker>
            ))}
          </MapContainer>
          </section>
        </section>

        <section className="list-panel">
          <div className="list-header">
            <h2>Visible trains</h2>
            <span>{listedTrains.length} shown</span>
          </div>
          <div className="train-list">
            {listedTrains.map(train => (
              <article className="train-row" key={train.trainId}>
                <span
                  className="line-dot"
                  style={{ '--line-color': colorForLine(train.primaryLineId) }}
                ></span>
                <div className="train-row-body">
                  <strong>{currentLocationLabelFor(train)}</strong>
                  <span>{serviceLabelFor(train)}</span>
                  {train.secondsToNextStop != null ? (
                    <span>{secondsLabelFor(train.secondsToNextStop)}</span>
                  ) : null}
                </div>
              </article>
            ))}

            {listedTrains.length === 0 ? (
              <div className="empty-state">No trains are available for the selected line.</div>
            ) : null}
          </div>
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

function TrainDetail({ label, children }) {
  return (
    <div className="train-detail">
      <span className="train-detail-label">{label}</span>
      <span>{children}</span>
    </div>
  )
}

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
    .map(train => ({
      ...train,
      primaryLineId: train.lineId,
      lineLabel: train.lineName
    }))
    .sort((leftTrain, rightTrain) => compareArrivalPriority(leftTrain, rightTrain))
}

function compareArrivalPriority(leftTrain, rightTrain) {
  if (leftTrain.secondsToNextStop == null && rightTrain.secondsToNextStop == null) {
    return leftTrain.lineLabel.localeCompare(rightTrain.lineLabel)
  }

  if (leftTrain.secondsToNextStop == null) {
    return 1
  }

  if (rightTrain.secondsToNextStop == null) {
    return -1
  }

  return leftTrain.secondsToNextStop - rightTrain.secondsToNextStop
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

function serviceLabelFor(train) {
  const destination = destinationLabelFor(train)

  if (train.towards != null && train.towards !== destination) {
    return `${train.lineLabel} to ${destination} via ${train.towards}`
  }

  return `${train.lineLabel} to ${destination}`
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

function markerLabelForLine(lineId) {
  const words = prettifyLineId(lineId).split(' ')

  if (words.length === 1) {
    return words[0].slice(0, 2).toUpperCase()
  }

  return words.map(word => word.charAt(0)).join('').slice(0, 2).toUpperCase()
}

function createTrainIcon(train) {
  const lineColor = colorForLine(train.primaryLineId)
  const headingDegrees = train.headingDegrees ?? 0
  const arrowClassName =
    train.headingDegrees == null ? 'train-marker-arrow train-marker-arrow--hidden' : 'train-marker-arrow'

  return L.divIcon({
    className: 'train-icon-shell',
    html: `
      <div class="train-marker" style="--line-color: ${lineColor}; --heading-degrees: ${headingDegrees}deg">
        <div class="${arrowClassName}"></div>
        <div class="train-icon">
          <span>${markerLabelForLine(train.primaryLineId)}</span>
        </div>
      </div>
    `,
    iconSize: [40, 40],
    iconAnchor: [20, 20],
    popupAnchor: [0, -22]
  })
}

export default App
