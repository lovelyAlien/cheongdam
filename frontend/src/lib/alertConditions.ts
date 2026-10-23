// 구독 조건 v1(SCHEMA.md 8장)은 화면 필터와 같은 키·값이다. 접수 상태·검색어·정렬은 담지 않는다.
import {
  AREA_OPTIONS,
  DEFAULT_STATUS,
  HOUSE_KIND_OPTIONS,
  labelOf,
  NOTICE_TYPE_OPTIONS,
  SUPPLY_OPTIONS,
  toParams,
  type Filters,
} from './filters'

export interface Conditions {
  v: 1
  region?: string[]
  supply?: string[]
  price_min?: number
  price_max?: number
  area?: string[]
  notice_type?: string[]
  house_kind?: string[]
}

export function toConditions(f: Filters): Conditions {
  const c: Conditions = { v: 1 }
  if (f.region.length) c.region = f.region
  if (f.supply.length) c.supply = f.supply
  if (f.price_min != null) c.price_min = f.price_min
  if (f.price_max != null) c.price_max = f.price_max
  if (f.area.length) c.area = f.area
  if (f.notice_type.length) c.notice_type = f.notice_type
  if (f.house_kind.length) c.house_kind = f.house_kind
  return c
}

/** 그 조건이 담긴 목록(S-01) 쿼리. */
export function conditionsToSearch(c: Conditions): string {
  const f: Filters = {
    q: '',
    status: DEFAULT_STATUS,
    region: c.region ?? [],
    supply: c.supply ?? [],
    price_min: c.price_min ?? null,
    price_max: c.price_max ?? null,
    area: c.area ?? [],
    notice_type: c.notice_type ?? [],
    house_kind: c.house_kind ?? [],
    sort: 'deadline',
  }
  return toParams(f).toString()
}

const labels = (opts: { value: string; label: string }[], values?: string[]) =>
  (values ?? []).map((v) => labelOf(opts, v))

/** `서울·경기 · 신혼부부 · 5억 이하` 같은 한 줄 요약. */
export function summarize(c: Conditions): string {
  const parts: string[] = []
  const region = c.region ?? []
  if (region.length) parts.push(region.length >= 3 ? `${region[0]} 외 ${region.length - 1}` : region.join('·'))
  parts.push(...[labels(SUPPLY_OPTIONS, c.supply).join('·')].filter(Boolean))
  if (c.price_min != null || c.price_max != null) {
    const lo = c.price_min != null ? `${c.price_min / 10000}억` : null
    const hi = c.price_max != null ? `${c.price_max / 10000}억` : null
    parts.push(lo && hi ? `${lo}~${hi}` : lo ? `${lo} 이상` : `${hi} 이하`)
  }
  parts.push(...[labels(AREA_OPTIONS, c.area).join('·')].filter(Boolean))
  parts.push(...[labels(NOTICE_TYPE_OPTIONS, c.notice_type).join('·')].filter(Boolean))
  parts.push(...[labels(HOUSE_KIND_OPTIONS, c.house_kind).join('·')].filter(Boolean))
  return parts.length ? parts.join(' · ') : '모든 공고'
}
