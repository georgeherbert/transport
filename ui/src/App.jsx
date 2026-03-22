import {
  startTransition,
  useDeferredValue,
  useEffect,
  useEffectEvent,
  useState
} from 'react'
import L from 'leaflet'
import { MapContainer, Marker, Popup, TileLayer } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'

const londonCenter = [51.5072, -0.1276]
const refreshIntervalMs = 20000

const linePalette = {
  bakerloo: '#9b5a20',
  central: '#d7261b',
  circle: '#f4c430',
  district: '#117d37',
  'hammersmith-city': '#e67ca6',
  jubilee: '#6d7b8a',
  metropolitan: '#7c1a63',
  northern: '#202124',
  piccadilly: '#1640b4',
  victoria: '#0097d7',
  'waterloo-city': '#67c6c2'
}

function App() {
  const [snapshot, setSnapshot] = useState(null)
  const [status, setStatus] = useState('loading')
  const [errorMessage, setErrorMessage] = useState('')
  const [selectedLineId, setSelectedLineId] = useState('all')

  const requestSnapshot = useEffectEvent(async forceRefresh => {
    setStatus(currentStatus => (snapshot == null ? 'loading' : currentStatus === 'ready' ? 'refreshing' : currentStatus))

    try {
      const endpoint = forceRefresh ? '/api/tubes/live?refresh=true' : '/api/tubes/live'
      const response = await fetch(endpoint, {
        headers: {
          Accept: 'application/json'
        }
      })
      const payload = await response.json()

      if (!response.ok) {
        throw new Error(payload.message ?? 'Unable to load the live tube feed.')
      }

      startTransition(() => {
        setSnapshot(payload)
        setErrorMessage('')
        setStatus('ready')
      })
    } catch (error) {
      const message =
        error instanceof Error ? error.message : 'Unable to load the live tube feed.'

      startTransition(() => {
        setErrorMessage(message)
        setStatus(snapshot == null ? 'error' : 'stale')
      })
    }
  })

  useEffect(() => {
    requestSnapshot(true)

    const intervalId = window.setInterval(() => {
      requestSnapshot(true)
    }, refreshIntervalMs)

    return () => {
      window.clearInterval(intervalId)
    }
  }, [])

  const deferredSelectedLineId = useDeferredValue(selectedLineId)
  const lineOptions = buildLineOptions(snapshot)
  const visibleTrains = buildVisibleTrains(snapshot, deferredSelectedLineId)
  const listedTrains = visibleTrains.slice(0, 12)

  return (
    <div className="page">
      <header className="topbar">
        <div>
          <h1>London Tube Map</h1>
          <p>Live Underground train positions from the TfL feed.</p>
        </div>
        <button className="refresh-button" type="button" onClick={() => requestSnapshot(true)}>
          Refresh
        </button>
      </header>

      <section className="status-row">
        <StatusItem label="Status" value={statusLabelFor(status)} />
        <StatusItem label="Generated" value={snapshot == null ? 'Waiting for data' : formatDateTime(snapshot.generatedAt)} />
        <StatusItem label="Trains" value={snapshot?.trainCount ?? '...'} />
        <StatusItem label="Plotted" value={visibleTrains.length} />
      </section>

      {errorMessage !== '' ? <div className="banner banner--error">{errorMessage}</div> : null}
      {snapshot?.partial ? (
        <div className="banner banner--warning">
          Live feed incomplete. Some upstream data was unavailable.
        </div>
      ) : null}

      <section className="toolbar">
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

        <div className="toolbar-meta">
          <span>Stations failed: {snapshot?.stationsFailed ?? '...'}</span>
          <span>Cached: {snapshot?.cached ? 'Yes' : 'No'}</span>
        </div>
      </section>

      <main className="layout">
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
              attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
              url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
            />

            {visibleTrains.map(train => (
              <Marker
                key={train.trainId}
                position={[train.coordinate.lat, train.coordinate.lon]}
                icon={createTrainIcon(train)}
              >
                <Popup>
                  <div className="popup-card">
                    <strong>{train.lineLabel}</strong>
                    <div>{train.destinationName ?? 'Destination unavailable'}</div>
                    <div>{train.currentLocation}</div>
                    <div>{secondsLabelFor(train.secondsToNextStop)}</div>
                  </div>
                </Popup>
              </Marker>
            ))}
          </MapContainer>
        </section>

        <aside className="list-panel">
          <h2>Visible trains</h2>

          <div className="train-list">
            {listedTrains.map(train => (
              <article className="train-row" key={train.trainId}>
                <span
                  className="line-dot"
                  style={{ '--line-color': colorForLine(train.primaryLineId) }}
                ></span>
                <div className="train-row-body">
                  <strong>{train.destinationName ?? train.lineLabel}</strong>
                  <span>{train.currentLocation}</span>
                  <span>{secondsLabelFor(train.secondsToNextStop)}</span>
                </div>
              </article>
            ))}

            {listedTrains.length === 0 ? (
              <div className="empty-state">No trains are available for the selected line.</div>
            ) : null}
          </div>
        </aside>
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

function buildLineOptions(snapshot) {
  if (snapshot == null) {
    return []
  }

  return snapshot.lines
    .map(lineId => ({
      id: lineId,
      name: prettifyLineId(lineId)
    }))
    .sort((leftLine, rightLine) => leftLine.name.localeCompare(rightLine.name))
}

function buildVisibleTrains(snapshot, selectedLineId) {
  if (snapshot == null) {
    return []
  }

  return snapshot.trains
    .map(train => {
      const coordinate = resolveCoordinate(train)

      if (coordinate == null) {
        return null
      }

      const primaryLineId = train.lineIds[0] ?? 'northern'

      return {
        ...train,
        coordinate,
        primaryLineId,
        lineLabel: train.lineNames[0] ?? prettifyLineId(primaryLineId)
      }
    })
    .filter(train => train != null)
    .filter(train => selectedLineId === 'all' || train.lineIds.includes(selectedLineId))
    .sort((leftTrain, rightTrain) => compareArrivalPriority(leftTrain, rightTrain))
}

function resolveCoordinate(train) {
  return (
    train.location.coordinate ??
    train.nextStop?.coordinate ??
    train.location.station?.coordinate ??
    train.location.toStation?.coordinate ??
    train.location.fromStation?.coordinate ??
    null
  )
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
    return 'Arrival time unavailable'
  }

  if (seconds < 60) {
    return `${seconds}s to next stop`
  }

  return `${Math.round(seconds / 60)}m to next stop`
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

  return L.divIcon({
    className: 'train-icon-shell',
    html: `
      <div class="train-icon" style="--line-color: ${lineColor}">
        <span>${markerLabelForLine(train.primaryLineId)}</span>
      </div>
    `,
    iconSize: [26, 26],
    iconAnchor: [13, 13],
    popupAnchor: [0, -14]
  })
}

export default App
