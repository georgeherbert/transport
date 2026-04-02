import { type ReactNode, memo, useEffect, useRef } from 'react'
import L, { type LeafletMouseEvent, type Map as LeafletMap } from 'leaflet'
import { MapContainer, Marker, Pane, Polyline, Popup, TileLayer, useMap, useMapEvents } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'

import {
  accentStyle,
  basemapAttribution,
  basemapUrl,
  colorForLine,
  createServiceIcon,
  createStationIcon,
  currentLocationLabelForService,
  destinationLabelForService,
  featurePickerPopupOffset,
  formatClockTime,
  leafletPosition,
  londonCenter,
  prettifyLineId,
  sortedLineIds,
  stationArrivalLabelFor
} from './railMapPresentation'
import type { VisibleLinePath } from './railMapModel'
import type {
  FeaturePickerFeature,
  FeaturePickerState,
  PlottedRailService,
  RailStation,
  SelectedLineId,
  SelectedMapFeature
} from '../types'

interface PopupCardProps {
  accentColor?: string | null
  children: ReactNode
  kicker?: string | null
  title: string
}

interface PopupRowProps {
  label: string
  value: string
}

interface LineBadgesProps {
  lineIds: string[]
}

interface MapZoomStateProps {
  onZoomEnd: () => void
  onZoomStart: () => void
}

interface OverlapFeaturePickerProps {
  featurePicker: FeaturePickerState
  onChoose: (feature: FeaturePickerFeature) => void
  onDismiss: () => void
}

interface RailMapViewProps {
  featurePicker: FeaturePickerState | null
  onChooseFeature: (feature: FeaturePickerFeature) => void
  onDismissFeaturePicker: () => void
  onFeatureClick: (feature: SelectedMapFeature, event: LeafletMouseEvent, map: LeafletMap) => void
  onZoomEnd: () => void
  onZoomStart: () => void
  plottedServices: PlottedRailService[]
  selectedLineId: SelectedLineId
  selectedServiceId: string | null
  selectedStationId: string | null
  visibleLinePaths: VisibleLinePath[]
  visibleStations: RailStation[]
  onDeselectService: (serviceId: string) => void
  onDeselectStation: (stationId: string) => void
}

interface ServiceMarkerProps {
  isSelected: boolean
  onDeselect: () => void
  onSelect: (feature: SelectedMapFeature, event: LeafletMouseEvent, map: LeafletMap) => void
  service: PlottedRailService
}

interface StaticMapLayersProps {
  isSelectedStation: (stationId: string) => boolean
  onDeselectStation: (stationId: string) => void
  onStationClick: (feature: SelectedMapFeature, event: LeafletMouseEvent, map: LeafletMap) => void
  selectedLineId: SelectedLineId
  visibleLinePaths: VisibleLinePath[]
  visibleStations: RailStation[]
}

interface StationMarkerProps {
  isSelected: boolean
  onDeselect: () => void
  onSelect: (feature: SelectedMapFeature, event: LeafletMouseEvent, map: LeafletMap) => void
  selectedLineId: SelectedLineId
  station: RailStation
}

export function RailMapView({
  featurePicker,
  onChooseFeature,
  onDismissFeaturePicker,
  onFeatureClick,
  onZoomEnd,
  onZoomStart,
  plottedServices,
  selectedLineId,
  selectedServiceId,
  selectedStationId,
  visibleLinePaths,
  visibleStations,
  onDeselectService,
  onDeselectStation
}: RailMapViewProps) {
  return (
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
          onZoomStart={onZoomStart}
          onZoomEnd={onZoomEnd}
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
          isSelectedStation={stationId => selectedStationId === stationId}
          onDeselectStation={onDeselectStation}
          onStationClick={onFeatureClick}
          selectedLineId={selectedLineId}
          visibleLinePaths={visibleLinePaths}
          visibleStations={visibleStations}
        />

        {plottedServices.map(service => (
          <ServiceMarker
            key={service.serviceId}
            isSelected={selectedServiceId === service.serviceId}
            onDeselect={() => onDeselectService(service.serviceId)}
            onSelect={onFeatureClick}
            service={service}
          />
        ))}

        {featurePicker != null ? (
          <OverlapFeaturePicker
            featurePicker={featurePicker}
            onChoose={onChooseFeature}
            onDismiss={onDismissFeaturePicker}
          />
        ) : null}
      </MapContainer>
    </section>
  )
}

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
  (previousProps, nextProps) => previousProps.featurePicker === nextProps.featurePicker
)

const ServiceMarker = memo(
  function ServiceMarker({ isSelected, onDeselect, onSelect, service }: ServiceMarkerProps) {
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
  (previousProps, nextProps) =>
    previousProps.isSelected === nextProps.isSelected &&
    previousProps.service === nextProps.service
)

const StationMarker = memo(
  function StationMarker({ isSelected, onDeselect, onSelect, selectedLineId, station }: StationMarkerProps) {
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
  (previousProps, nextProps) =>
    previousProps.selectedLineId === nextProps.selectedLineId &&
    previousProps.isSelected === nextProps.isSelected &&
    previousProps.station === nextProps.station
)

const StaticMapLayers = memo(
  function StaticMapLayers({
    isSelectedStation,
    onDeselectStation,
    onStationClick,
    selectedLineId,
    visibleLinePaths,
    visibleStations
  }: StaticMapLayersProps) {
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
            isSelected={isSelectedStation(station.id)}
            onDeselect={() => onDeselectStation(station.id)}
            onSelect={onStationClick}
            selectedLineId={selectedLineId}
            station={station}
          />
        ))}
      </>
    )
  },
  (previousProps, nextProps) =>
    previousProps.selectedLineId === nextProps.selectedLineId &&
    previousProps.visibleLinePaths === nextProps.visibleLinePaths &&
    previousProps.visibleStations === nextProps.visibleStations
)

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

function MapZoomState({ onZoomEnd, onZoomStart }: MapZoomStateProps) {
  useMapEvents({
    zoomstart: onZoomStart,
    zoomend: onZoomEnd
  })

  return null
}

function PopupCard({ accentColor, children, kicker, title }: PopupCardProps) {
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
