import { useEffect, useEffectEvent, useRef, useState } from 'react'
import type { LeafletMouseEvent, Map as LeafletMap } from 'leaflet'

import { overlappingFeaturesForFeature, prioritizedOverlapFeatures } from '../map/railMapPresentation'
import type {
  FeaturePickerFeature,
  FeaturePickerState,
  PlottedRailService,
  RailStation,
  SelectedLineId,
  SelectedMapFeature
} from '../types'

interface MapFeatureSelection {
  chooseMapFeature: (feature: FeaturePickerFeature) => void
  deselectService: (serviceId: string) => void
  deselectStation: (stationId: string) => void
  dismissFeaturePicker: () => void
  featurePicker: FeaturePickerState | null
  handleFeatureClick: (feature: SelectedMapFeature, event: LeafletMouseEvent, map: LeafletMap) => void
  selectedServiceId: string | null
  selectedStationId: string | null
}

export function useMapFeatureSelection(
  selectedLineId: SelectedLineId,
  plottedServices: PlottedRailService[],
  visibleStations: RailStation[]
): MapFeatureSelection {
  const [selectedMapFeature, setSelectedMapFeature] = useState<SelectedMapFeature | null>(null)
  const [featurePicker, setFeaturePicker] = useState<FeaturePickerState | null>(null)
  const plottedServicesRef = useRef<PlottedRailService[]>(plottedServices)
  const visibleStationsRef = useRef<RailStation[]>(visibleStations)
  plottedServicesRef.current = plottedServices
  visibleStationsRef.current = visibleStations

  useEffect(() => {
    setSelectedMapFeature(null)
    setFeaturePicker(null)
  }, [selectedLineId])

  const chooseMapFeature = useEffectEvent((feature: FeaturePickerFeature) => {
    setFeaturePicker(null)
    setSelectedMapFeature({
      kind: feature.kind,
      id: feature.id
    })
  })

  const handleFeatureClick = useEffectEvent((
    clickedFeature: SelectedMapFeature,
    event: LeafletMouseEvent,
    map: LeafletMap
  ) => {
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
  })

  return {
    chooseMapFeature,
    deselectService: serviceId => {
      setSelectedMapFeature(currentFeature =>
        currentFeature?.kind === 'service' && currentFeature.id === serviceId
          ? null
          : currentFeature
      )
    },
    deselectStation: stationId => {
      setSelectedMapFeature(currentFeature =>
        currentFeature?.kind === 'station' && currentFeature.id === stationId
          ? null
          : currentFeature
      )
    },
    dismissFeaturePicker: () => {
      setFeaturePicker(null)
    },
    featurePicker,
    handleFeatureClick,
    selectedServiceId: selectedMapFeature?.kind === 'service' ? selectedMapFeature.id : null,
    selectedStationId: selectedMapFeature?.kind === 'station' ? selectedMapFeature.id : null
  }
}
