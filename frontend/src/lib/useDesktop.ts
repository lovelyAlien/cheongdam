import { useSyncExternalStore } from 'react'

// 이 폭부터 필터를 시트 대신 왼쪽 고정 패널로 보여 준다(SCREENS S-02)
const QUERY = '(min-width: 960px)'

function subscribe(cb: () => void) {
  const mql = window.matchMedia(QUERY)
  mql.addEventListener('change', cb)
  return () => mql.removeEventListener('change', cb)
}

export function useDesktop(): boolean {
  return useSyncExternalStore(subscribe, () => window.matchMedia(QUERY).matches, () => false)
}
