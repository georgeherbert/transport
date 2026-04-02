export type MapStatus = 'error' | 'live' | 'loading' | 'stale'

export function statusLabelFor(status: MapStatus): string {
  if (status === 'loading') {
    return 'Loading'
  }

  if (status === 'stale') {
    return 'Stale'
  }

  if (status === 'error') {
    return 'Unavailable'
  }

  return 'Live'
}
