import { startTransition, useEffect, useEffectEvent, useRef, useState } from 'react'

import { reconcileServices, reconcileStations } from '../snapshotReconciler'
import {
  railMapDynamicStateFromUnknown,
  railMapSnapshotFromUnknown,
  transportErrorMessageFromUnknown
} from '../transportApi'
import type { RailMapDynamicState, RailMapSnapshot } from '../types'
import type { MapStatus } from './mapStatus'

interface RailMapFeedState {
  errorMessage: string
  mapSnapshot: RailMapSnapshot | null
  pauseLiveMapUpdates: () => void
  resumeLiveMapUpdates: () => void
  status: MapStatus
}

export function useRailMapFeed(): RailMapFeedState {
  const [mapSnapshot, setMapSnapshot] = useState<RailMapSnapshot | null>(null)
  const [status, setStatus] = useState<MapStatus>('loading')
  const [errorMessage, setErrorMessage] = useState('')
  const pendingSnapshotRef = useRef<RailMapSnapshot | null>(null)
  const pendingServicePositionsRef = useRef<RailMapDynamicState | null>(null)
  const servicePositionsAnimationFrameRef = useRef<number | null>(null)
  const isZoomingRef = useRef(false)

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
          generatedAt: servicePositions.generatedAt,
          serviceCount: servicePositions.serviceCount,
          stations: reconcileStations(currentSnapshot.stations, servicePositions.stations),
          services: reconcileServices(currentSnapshot.services, servicePositions.services)
        }
      })
      setErrorMessage('')
      setStatus('live')
    })
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

  return {
    errorMessage,
    mapSnapshot,
    pauseLiveMapUpdates,
    resumeLiveMapUpdates,
    status
  }
}
