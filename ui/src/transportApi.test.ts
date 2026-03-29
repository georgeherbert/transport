import assert from 'node:assert/strict'
import test from 'node:test'

import { railMapDynamicStateFromUnknown, railMapSnapshotFromUnknown, transportErrorMessageFromUnknown } from './transportApi'

test('railMapSnapshotFromUnknown accepts a valid snapshot payload', () => {
  const payload = {
    source: 'tfl',
    generatedAt: '2026-03-29T12:00:00Z',
    cached: false,
    cacheAgeSeconds: 0,
    stationsQueried: 10,
    stationsFailed: 0,
    partial: false,
    serviceCount: 1,
    lines: [
      {
        id: 'jubilee',
        name: 'Jubilee',
        paths: [
          {
            coordinates: [
              {
                lat: 51.5,
                lon: -0.1
              }
            ]
          }
        ]
      }
    ],
    stations: [
      {
        id: 'station-1',
        name: 'Baker Street',
        coordinate: {
          lat: 51.5226,
          lon: -0.1571
        },
        lineIds: ['jubilee'],
        arrivals: [
          {
            serviceId: 'service-1',
            lineId: 'jubilee',
            destinationName: 'Stratford',
            expectedArrival: '2026-03-29T12:01:00Z'
          }
        ]
      }
    ],
    services: [
      {
        serviceId: 'service-1',
        vehicleId: 'vehicle-1',
        lineId: 'jubilee',
        lineName: 'Jubilee',
        direction: 'eastbound',
        destinationName: 'Stratford',
        towards: 'Stratford',
        currentLocation: 'Baker Street',
        coordinate: {
          lat: 51.5226,
          lon: -0.1571
        },
        headingDegrees: 90,
        expectedArrival: '2026-03-29T12:01:00Z',
        observedAt: '2026-03-29T12:00:00Z',
        futureArrivals: [
          {
            stationId: 'station-1',
            stationName: 'Baker Street',
            expectedArrival: '2026-03-29T12:01:00Z'
          }
        ]
      }
    ]
  }

  const snapshot = railMapSnapshotFromUnknown(payload)

  assert.equal(snapshot.lines[0]?.name, 'Jubilee')
  assert.equal(snapshot.services[0]?.vehicleId, 'vehicle-1')
})

test('railMapDynamicStateFromUnknown rejects payloads without services', () => {
  const invalidPayload = {
    source: 'tfl',
    generatedAt: '2026-03-29T12:00:00Z',
    cached: false,
    cacheAgeSeconds: 0,
    stationsQueried: 10,
    stationsFailed: 0,
    partial: false,
    serviceCount: 1,
    stations: []
  }

  assert.throws(() => railMapDynamicStateFromUnknown(invalidPayload))
})

test('transportErrorMessageFromUnknown returns fallback for malformed payloads', () => {
  const message = transportErrorMessageFromUnknown({ unexpected: true }, 'fallback message')

  assert.equal(message, 'fallback message')
})
