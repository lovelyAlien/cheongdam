import type { Unit } from '../api/types'

// 필터 상태 = URL 쿼리 = API 쿼리 = 구독 conditions. 같은 이름과 형식을 쓴다(SCREENS 1장, API.md 2장).

export const PAGE_SIZE = 20

export const REGIONS = [
  '서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종', '경기',
  '강원', '충북', '충남', '전북', '전남', '경북', '경남', '제주',
]
export const CAPITAL_AREA = ['서울', '경기', '인천']

export const STATUS_OPTIONS = [
  { value: 'upcoming', label: '접수 예정' },
  { value: 'open', label: '접수 중' },
  { value: 'closed', label: '마감' },
]
export const SUPPLY_OPTIONS = [
  { value: 'newlywed', label: '신혼부부' },
  { value: 'first_home', label: '생애최초' },
  { value: 'multi_child', label: '다자녀' },
  { value: 'newborn', label: '신생아' },
  { value: 'elderly_parent', label: '노부모부양' },
  { value: 'youth', label: '청년' },
  { value: 'institution', label: '기관추천' },
]
export const AREA_OPTIONS = [
  { value: 'le60', label: '60㎡ 이하' },
  { value: '60to85', label: '60~85㎡' },
  { value: 'gt85', label: '85㎡ 초과' },
]
export const NOTICE_TYPE_OPTIONS = [
  { value: 'general', label: '일반 분양' },
  { value: 'no_rank', label: '무순위' },
  { value: 'resupply', label: '불법행위 재공급' },
]
export const HOUSE_KIND_OPTIONS = [
  { value: 'private', label: '민영' },
  { value: 'public', label: '국민' },
]
export const SORT_OPTIONS = [
  { value: 'deadline', label: '접수 임박순' },
  { value: 'latest', label: '최신 공고순' },
]

export const DEFAULT_STATUS = ['upcoming', 'open']

export interface Filters {
  q: string
  status: string[]
  region: string[]
  supply: string[]
  price_min: number | null
  price_max: number | null
  area: string[]
  notice_type: string[]
  house_kind: string[]
  sort: string
}

const list = (v: string | null) => (v ? v.split(',').filter(Boolean) : [])
const num = (v: string | null) => (v && /^\d+$/.test(v) ? Number(v) : null)

export function parseFilters(p: URLSearchParams): Filters {
  const status = list(p.get('status'))
  return {
    q: p.get('q') ?? '',
    status: status.length ? status : DEFAULT_STATUS,
    region: list(p.get('region')),
    supply: list(p.get('supply')),
    price_min: num(p.get('price_min')),
    price_max: num(p.get('price_max')),
    area: list(p.get('area')),
    notice_type: list(p.get('notice_type')),
    house_kind: list(p.get('house_kind')),
    sort: p.get('sort') === 'latest' ? 'latest' : 'deadline',
  }
}

/** 기본값과 같은 조건은 URL에 쓰지 않는다. */
export function toParams(f: Filters): URLSearchParams {
  const p = new URLSearchParams()
  if (f.q) p.set('q', f.q)
  if (!sameSet(f.status, DEFAULT_STATUS)) p.set('status', f.status.join(','))
  if (f.region.length) p.set('region', f.region.join(','))
  if (f.supply.length) p.set('supply', f.supply.join(','))
  if (f.price_min != null) p.set('price_min', String(f.price_min))
  if (f.price_max != null) p.set('price_max', String(f.price_max))
  if (f.area.length) p.set('area', f.area.join(','))
  if (f.notice_type.length) p.set('notice_type', f.notice_type.join(','))
  if (f.house_kind.length) p.set('house_kind', f.house_kind.join(','))
  if (f.sort !== 'deadline') p.set('sort', f.sort)
  return p
}

function sameSet(a: string[], b: string[]) {
  return a.length === b.length && a.every((x) => b.includes(x))
}

/** `필터 N`의 N: 검색어·정렬을 뺀, 기본값과 다른 조건 개수. */
export function activeFilterCount(f: Filters): number {
  return (
    (sameSet(f.status, DEFAULT_STATUS) ? 0 : 1) +
    (f.region.length ? 1 : 0) +
    (f.supply.length ? 1 : 0) +
    (f.price_min != null || f.price_max != null ? 1 : 0) +
    (f.area.length ? 1 : 0) +
    (f.notice_type.length ? 1 : 0) +
    (f.house_kind.length ? 1 : 0)
  )
}

export function labelOf(options: { value: string; label: string }[], value: string) {
  return options.find((o) => o.value === value)?.label ?? value
}

/**
 * 상세 화면으로 넘기는 주택형 단위 조건(특별공급·분양가·전용면적, F3-5~7).
 * 상세는 이 조건으로 일치하는 주택형 행을 강조하고 블록 C의 기본 유형을 고른다.
 */
export function toDetailParams(f: Filters): string {
  const p = new URLSearchParams()
  if (f.supply.length) p.set('supply', f.supply.join(','))
  if (f.price_min != null) p.set('price_min', String(f.price_min))
  if (f.price_max != null) p.set('price_max', String(f.price_max))
  if (f.area.length) p.set('area', f.area.join(','))
  return p.toString()
}

export function hasUnitFilter(f: Filters): boolean {
  return f.supply.length > 0 || f.price_min != null || f.price_max != null || f.area.length > 0
}

const AREA_RANGES: Record<string, (a: number) => boolean> = {
  le60: (a) => a <= 60,
  '60to85': (a) => a > 60 && a <= 85,
  gt85: (a) => a > 85,
}

/** 한 주택형이 주택형 단위 조건을 모두 만족하는가(F3-9). 서버 필터와 같은 규칙이다. */
export function unitMatches(u: Unit, f: Filters): boolean {
  if (f.supply.length && !f.supply.some((c) => (u.special_by_type?.[c] ?? 0) > 0)) return false
  if (f.price_min != null && (u.top_price_man == null || u.top_price_man < f.price_min)) return false
  if (f.price_max != null && (u.top_price_man == null || u.top_price_man > f.price_max)) return false
  if (f.area.length && !f.area.some((code) => AREA_RANGES[code]?.(u.exclusive_area))) return false
  return true
}
