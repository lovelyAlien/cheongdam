import { useEffect, useRef, useState } from 'react'
import type { MapPin, NoticeItem } from '../api/types'
import { loadKakao, type KakaoMaps, type KMap, type KOverlay } from '../lib/kakaoMap'

const STATUS_TEXT: Record<string, string> = {
  open: '접수 중',
  upcoming: '접수 예정',
  closed: '마감',
  unknown: '일정 미정',
}

// 이 크기(px)의 칸 안에 든 핀은 한 묶음으로 보여 준다. 핀 글자 폭을 넘지 않게 잡았다
const CELL_PX = 90

const FAIL_TEXT = '지도를 불러오지 못했어요. 목록으로 볼 수 있어요.'

/** SDK 로딩 상태. 실패하면 null이 아닌 'fail'. */
function useKakao(): KakaoMaps | 'fail' | null {
  const [sdk, setSdk] = useState<KakaoMaps | 'fail' | null>(null)
  useEffect(() => {
    let alive = true
    loadKakao().then((k) => alive && setSdk(k), () => alive && setSdk('fail'))
    return () => {
      alive = false
    }
  }, [])
  return sdk
}

function pinEl(cls: string, text: string, note: string | null, selected: boolean, onClick?: () => void) {
  const el = document.createElement(onClick ? 'button' : 'div')
  el.className = `map-pin ${cls}${selected ? ' sel' : ''}`
  el.textContent = text // textContent만 써서 공고 이름 등이 HTML로 해석되지 않게 한다
  if (note) {
    const n = document.createElement('span')
    n.className = 'map-pin-note'
    n.textContent = note
    el.appendChild(n)
  }
  if (onClick) {
    el.setAttribute('type', 'button')
    el.onclick = onClick
  }
  return el
}

export function NoticeMap({
  pins,
  items,
  selected,
  onSelect,
  onList,
}: {
  pins: MapPin[]
  items: NoticeItem[]
  selected: string[]
  onSelect: (nos: string[]) => void
  onList: () => void
}) {
  const sdk = useKakao()
  const box = useRef<HTMLDivElement>(null)
  const map = useRef<KMap | null>(null)
  const fitted = useRef<MapPin[] | null>(null)
  const [level, setLevel] = useState(0) // 확대·축소 때 다시 묶는다

  useEffect(() => {
    if (!box.current || !sdk || sdk === 'fail') return
    if (!map.current) {
      const created = new sdk.Map(box.current, { center: new sdk.LatLng(36.5, 127.8), level: 12 })
      sdk.event.addListener(created, 'zoom_changed', () => setLevel(created.getLevel()))
      map.current = created
    }
    const m = map.current
    const status = new Map(items.map((i) => [i.house_manage_no, i.status]))
    // 선택만 바뀐 렌더에서는 지도를 움직이지 않는다. 묶기 전에 맞춰야 현재 배율로 묶인다
    if (fitted.current !== pins && pins.length > 0) {
      fitted.current = pins
      if (pins.every((p) => p.lat === pins[0].lat && p.lng === pins[0].lng)) {
        m.setCenter(new sdk.LatLng(pins[0].lat, pins[0].lng))
        m.setLevel(5)
      } else {
        const bounds = new sdk.LatLngBounds()
        for (const p of pins) bounds.extend(new sdk.LatLng(p.lat, p.lng))
        m.setBounds(bounds)
      }
    }
    // 같은 좌표(시군구 중심 포함)나 화면에서 가까운 핀은 건수 묶음으로
    const proj = m.getProjection()
    const groups = new Map<string, MapPin[]>()
    for (const p of pins) {
      const pt = proj.containerPointFromCoords(new sdk.LatLng(p.lat, p.lng))
      const k = `${Math.floor(pt.x / CELL_PX)},${Math.floor(pt.y / CELL_PX)}`
      groups.set(k, [...(groups.get(k) ?? []), p])
    }
    const overlays: KOverlay[] = []
    for (const g of groups.values()) {
      const pos = new sdk.LatLng(g[0].lat, g[0].lng)
      const nos = g.map((p) => p.house_manage_no)
      const sel = nos.some((n) => selected.includes(n))
      const approx = g.every((p) => p.accuracy === 'district') ? '대략적 위치' : null
      const el =
        g.length > 1
          ? pinEl('group', `${g.length}건`, approx, sel, () => onSelect(nos))
          : pinEl(status.get(nos[0]) ?? 'unknown', STATUS_TEXT[status.get(nos[0]) ?? 'unknown'], approx, sel, () => onSelect(nos))
      overlays.push(new sdk.CustomOverlay({ position: pos, content: el, yAnchor: 1, map: m }))
    }
    return () => overlays.forEach((o) => o.setMap(null))
  }, [sdk, pins, items, selected, onSelect, level])

  if (sdk === 'fail') {
    return (
      <div className="map-fail">
        <p>{FAIL_TEXT}</p>
        <button type="button" className="btn" onClick={onList}>목록 보기</button>
      </div>
    )
  }
  return <div ref={box} className="map-canvas" role="region" aria-label="공고 지도" />
}

/** 상세 위치 미니맵(F9-2). 실패하면 아무것도 그리지 않아 주소 글자만 남는다. */
export function MiniMap({
  lat,
  lng,
  name,
  accuracy,
}: {
  lat: number
  lng: number
  name: string
  accuracy: 'exact' | 'district'
}) {
  const sdk = useKakao()
  const box = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!box.current || !sdk || sdk === 'fail') return
    const pos = new sdk.LatLng(lat, lng)
    const m = new sdk.Map(box.current, { center: pos, level: accuracy === 'exact' ? 3 : 7, draggable: false, scrollwheel: false })
    const o = new sdk.CustomOverlay({ position: pos, content: pinEl('open', '●', null, false), yAnchor: 1, map: m })
    return () => o.setMap(null)
  }, [sdk, lat, lng, accuracy])

  if (sdk === 'fail') return null
  return (
    <div className="minimap">
      <div ref={box} className="minimap-canvas" role="img" aria-label={`${name} 위치 지도`} />
      {accuracy === 'district' ? (
        <p className="muted small">대략적 위치예요. 정확한 주소를 변환하지 못했어요.</p>
      ) : (
        <a
          className="btn small-btn"
          href={`https://map.kakao.com/link/map/${encodeURIComponent(name)},${lat},${lng}`}
          target="_blank"
          rel="noreferrer"
        >
          카카오맵에서 보기
        </a>
      )}
    </div>
  )
}
