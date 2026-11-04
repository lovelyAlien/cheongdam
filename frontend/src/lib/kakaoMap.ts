// 카카오맵 JavaScript SDK를 한 번만 불러온다. 키가 없거나 로딩이 실패하면 reject → 화면은 E-21 안내를 보여 준다.
interface KMap {
  setBounds(b: unknown): void
  setCenter(p: unknown): void
  setLevel(n: number): void
  relayout(): void
}
interface KOverlay {
  setMap(m: KMap | null): void
}
export interface KakaoMaps {
  load(cb: () => void): void
  LatLng: new (lat: number, lng: number) => unknown
  LatLngBounds: new () => { extend(p: unknown): void }
  Map: new (el: HTMLElement, o: { center: unknown; level: number; draggable?: boolean; scrollwheel?: boolean }) => KMap
  CustomOverlay: new (o: { position: unknown; content: HTMLElement; yAnchor?: number; map: KMap }) => KOverlay
}
export type { KMap, KOverlay }

const TIMEOUT_MS = 10000
let loading: Promise<KakaoMaps> | null = null

export function loadKakao(): Promise<KakaoMaps> {
  if (loading) return loading
  const key = import.meta.env.VITE_KAKAO_JS_KEY as string | undefined
  loading = new Promise<KakaoMaps>((resolve, reject) => {
    if (!key) return reject(new Error('VITE_KAKAO_JS_KEY 없음'))
    const s = document.createElement('script')
    const timer = setTimeout(() => reject(new Error('카카오맵 로딩 시간 초과')), TIMEOUT_MS)
    s.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(key)}&autoload=false`
    s.onload = () => {
      const maps = (window as unknown as { kakao?: { maps?: KakaoMaps } }).kakao?.maps
      if (!maps) return reject(new Error('카카오맵 SDK 오류'))
      maps.load(() => {
        clearTimeout(timer)
        resolve(maps)
      })
    }
    s.onerror = () => reject(new Error('카카오맵 SDK 로딩 실패'))
    document.head.appendChild(s)
  }).catch((e) => {
    loading = null // 일시 오류면 다음 진입 때 다시 시도한다
    throw e
  })
  return loading
}
