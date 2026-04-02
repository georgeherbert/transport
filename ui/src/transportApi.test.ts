import assert from 'node:assert/strict'
import test from 'node:test'

import { railMapDynamicStateFromUnknown, railMapSnapshotFromUnknown, transportErrorMessageFromUnknown } from './transportApi'

test('railMapSnapshotFromUnknown accepts a valid snapshot payload', () => {
  const payload = {
    generatedAt: '2026-03-29T12:00:00Z',
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
        lineId: 'jubilee',
        lineName: 'Jubilee',
        destinationName: 'Stratford',
        towards: 'Stratford',
        currentLocation: 'Baker Street',
        coordinate: {
          lat: 51.5226,
          lon: -0.1571
        },
        headingDegrees: 90,
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
  assert.equal(snapshot.services[0]?.lineName, 'Jubilee')
})

test('railMapDynamicStateFromUnknown rejects payloads without services', () => {
  const invalidPayload = {
    generatedAt: '2026-03-29T12:00:00Z',
    serviceCount: 1,
    stations: []
  }

  assert.throws(() => railMapDynamicStateFromUnknown(invalidPayload))
})

test('transportErrorMessageFromUnknown returns fallback for malformed payloads', () => {
  const message = transportErrorMessageFromUnknown({ unexpected: true }, 'fallback message')

  assert.equal(message, 'fallback message')
})
