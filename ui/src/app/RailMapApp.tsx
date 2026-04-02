import { useDeferredValue, useState } from 'react'

import { statusLabelFor } from './mapStatus'
import { useMapFeatureSelection } from './useMapFeatureSelection'
import { useRailMapFeed } from './useRailMapFeed'
import { RailMapView } from '../map/RailMapView'
import {
  buildLineOptions,
  buildVisibleLinePaths,
  buildVisibleServices,
  buildVisibleStations,
  isPlottedService
} from '../map/railMapModel'
import { formatDateTime } from '../map/railMapPresentation'
import type { SelectedLineId } from '../types'

function RailMapApp() {
  const [selectedLineId, setSelectedLineId] = useState<SelectedLineId>('all')
  const deferredSelectedLineId = useDeferredValue(selectedLineId)
  const {
    errorMessage,
    mapSnapshot,
    pauseLiveMapUpdates,
    resumeLiveMapUpdates,
    status
  } = useRailMapFeed()
  const lineOptions = buildLineOptions(mapSnapshot)
  const visibleLinePaths = buildVisibleLinePaths(mapSnapshot, deferredSelectedLineId)
  const visibleServices = buildVisibleServices(mapSnapshot, deferredSelectedLineId)
  const plottedServices = visibleServices.filter(isPlottedService)
  const visibleStations = buildVisibleStations(mapSnapshot, deferredSelectedLineId)
  const {
    chooseMapFeature,
    deselectService,
    deselectStation,
    dismissFeaturePicker,
    featurePicker,
    handleFeatureClick,
    selectedServiceId,
    selectedStationId
  } = useMapFeatureSelection(deferredSelectedLineId, plottedServices, visibleStations)

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
            </div>
          </div>

          <RailMapView
            featurePicker={featurePicker}
            onChooseFeature={chooseMapFeature}
            onDismissFeaturePicker={dismissFeaturePicker}
            onFeatureClick={handleFeatureClick}
            onZoomEnd={resumeLiveMapUpdates}
            onZoomStart={pauseLiveMapUpdates}
            plottedServices={plottedServices}
            selectedLineId={deferredSelectedLineId}
            selectedServiceId={selectedServiceId}
            selectedStationId={selectedStationId}
            visibleLinePaths={visibleLinePaths}
            visibleStations={visibleStations}
            onDeselectService={deselectService}
            onDeselectStation={deselectStation}
          />
        </section>
      </main>
    </div>
  )
}

function StatusItem({ label, value }: { label: string; value: number | string }) {
  return (
    <div className="status-item">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  )
}

export default RailMapApp
