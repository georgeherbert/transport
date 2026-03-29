import assert from 'node:assert/strict'
import test from 'node:test'

import { reconcileServices, reconcileStations } from './snapshotReconciler'
import type { RailService, RailStation } from './types'

function sampleService(overrides: Partial<RailService>): RailService {
  return {
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
        stationId: '940GZZLUBST',
        stationName: 'Baker Street',
        expectedArrival: '2026-03-29T12:01:00Z'
      }
    ],
    ...overrides
  }
}

function sampleStation(overrides: Partial<RailStation>): RailStation {
  return {
    id: 'station-1',
    name: 'Baker Street',
    coordinate: {
      lat: 51.5226,
      lon: -0.1571
    },
    lineIds: ['jubilee'],
    arrivals: [],
    ...overrides
  }
}

test('reconcileServices reuses unchanged service objects', () => {
  const previousServices = [
    sampleService({
      futureArrivals: [
        {
          stationId: '940GZZLUBST',
          stationName: 'Baker Street',
          expectedArrival: '2026-03-29T12:00:00Z'
        }
      ]
    })
  ]

  const nextServices = [
    sampleService({
      futureArrivals: [
        {
          stationId: '940GZZLUBST',
          stationName: 'Baker Street',
          expectedArrival: '2026-03-29T12:00:00Z'
        }
      ]
    })
  ]

  const reconciledServices = reconcileServices(previousServices, nextServices)

  assert.equal(reconciledServices, previousServices)
  assert.equal(reconciledServices[0], previousServices[0])
})

test('reconcileServices replaces a service when nested data changes', () => {
  const previousServices = [
    sampleService({
      futureArrivals: [
        {
          stationId: '940GZZLUBST',
          stationName: 'Baker Street',
          expectedArrival: '2026-03-29T12:00:00Z'
        }
      ]
    })
  ]

  const nextServices = [
    sampleService({
      futureArrivals: [
        {
          stationId: '940GZZLUBST',
          stationName: 'Baker Street',
          expectedArrival: '2026-03-29T12:00:05Z'
        }
      ]
    })
  ]

  const reconciledServices = reconcileServices(previousServices, nextServices)

  assert.notEqual(reconciledServices, previousServices)
  assert.notEqual(reconciledServices[0], previousServices[0])
  assert.deepEqual(reconciledServices, nextServices)
})

test('reconcileStations reuses unchanged station objects', () => {
  const previousStations = [
    sampleStation({
      lineIds: ['jubilee', 'metropolitan'],
      arrivals: [
        {
          serviceId: 'service-1',
          lineId: 'jubilee',
          destinationName: 'Stratford',
          expectedArrival: '2026-03-29T12:00:00Z'
        }
      ]
    })
  ]

  const nextStations = [
    sampleStation({
      lineIds: ['jubilee', 'metropolitan'],
      arrivals: [
        {
          serviceId: 'service-1',
          lineId: 'jubilee',
          destinationName: 'Stratford',
          expectedArrival: '2026-03-29T12:00:00Z'
        }
      ]
    })
  ]

  const reconciledStations = reconcileStations(previousStations, nextStations)

  assert.equal(reconciledStations, previousStations)
  assert.equal(reconciledStations[0], previousStations[0])
})

test('reconcileStations keeps array identity when nothing changes across multiple stations', () => {
  const previousStations = [
    sampleStation({}),
    sampleStation({
      id: 'station-2',
      name: 'Bond Street',
      coordinate: {
        lat: 51.5142,
        lon: -0.1494
      }
    })
  ]

  const nextStations = [
    sampleStation({}),
    sampleStation({
      id: 'station-2',
      name: 'Bond Street',
      coordinate: {
        lat: 51.5142,
        lon: -0.1494
      }
    })
  ]

  const reconciledStations = reconcileStations(previousStations, nextStations)

  assert.equal(reconciledStations, previousStations)
})

test('reconcileServices ignores plain-object property order differences', () => {
  const previousServices = [
    sampleService({
      futureArrivals: [
        {
          expectedArrival: '2026-03-29T12:01:00Z',
          stationName: 'Baker Street',
          stationId: 'station-1'
        }
      ]
    })
  ]

  const nextServices = [
    sampleService({
      futureArrivals: [
        {
          stationId: 'station-1',
          expectedArrival: '2026-03-29T12:01:00Z',
          stationName: 'Baker Street'
        }
      ],
      coordinate: {
        lon: -0.1571,
        lat: 51.5226
      }
    })
  ]

  const reconciledServices = reconcileServices(previousServices, nextServices)

  assert.equal(reconciledServices, previousServices)
})
