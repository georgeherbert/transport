import type { RailService, RailStation } from './types'

export function reconcileServices(previousServices: RailService[], nextServices: RailService[]): RailService[] {
  return reconcileById(previousServices, nextServices, service => service.serviceId)
}

export function reconcileStations(previousStations: RailStation[], nextStations: RailStation[]): RailStation[] {
  return reconcileById(previousStations, nextStations, station => station.id)
}

function reconcileById<Item extends object>(
  previousItems: Item[],
  nextItems: Item[],
  itemId: (item: Item) => string
): Item[] {
  const previousItemsById = new Map(
    previousItems.map(previousItem => [
      itemId(previousItem),
      {
        item: previousItem,
        serialized: stableSerialize(previousItem)
      }
    ])
  )
  let changed = previousItems.length !== nextItems.length

  const reconciledItems = nextItems.map(nextItem => {
    const previousItem = previousItemsById.get(itemId(nextItem))
    const serializedNextItem = stableSerialize(nextItem)

    if (previousItem == null || previousItem.serialized !== serializedNextItem) {
      changed = true
      return nextItem
    }

    return previousItem.item
  })

  return changed ? reconciledItems : previousItems
}

function stableSerialize(value: unknown): string {
  if (Array.isArray(value)) {
    return `[${value.map(stableSerialize).join(',')}]`
  }

  if (isRecord(value)) {
    const entries = Object.keys(value)
      .sort((leftKey, rightKey) => leftKey.localeCompare(rightKey))
      .map(key => `${JSON.stringify(key)}:${stableSerialize(value[key])}`)

    return `{${entries.join(',')}}`
  }

  return JSON.stringify(value)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null
}
