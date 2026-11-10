import { useCallback, useMemo, useSyncExternalStore } from 'react'

// 관심 공고(F3-12)는 서버에 보내지 않고 이 브라우저에만 둔다(D-32)
const KEY = 'favorites'
const EVENT = 'favorites-change'

function raw(): string {
  try {
    return localStorage.getItem(KEY) ?? '[]'
  } catch {
    return '[]'
  }
}

function parse(r: string): string[] {
  try {
    const v: unknown = JSON.parse(r)
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []
  } catch {
    return []
  }
}

function subscribe(cb: () => void) {
  // storage: 다른 탭, EVENT: 같은 탭의 다른 ♡
  window.addEventListener('storage', cb)
  window.addEventListener(EVENT, cb)
  return () => {
    window.removeEventListener('storage', cb)
    window.removeEventListener(EVENT, cb)
  }
}

export function useFavorites() {
  const r = useSyncExternalStore(subscribe, raw)
  const ids = useMemo(() => parse(r), [r])
  const toggle = useCallback((no: string) => {
    const cur = parse(raw())
    const next = cur.includes(no) ? cur.filter((x) => x !== no) : [...cur, no]
    try {
      localStorage.setItem(KEY, JSON.stringify(next))
    } catch {
      return // 저장소를 쓸 수 없는 브라우저(사생활 보호 모드 등)에서는 표시만 바뀌지 않는다
    }
    window.dispatchEvent(new Event(EVENT))
  }, [])
  return { ids, toggle }
}
