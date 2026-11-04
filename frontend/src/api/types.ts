// API.md 2장의 응답. 필드 이름은 서버와 같은 snake_case다.
export type NoticeStatus = 'upcoming' | 'open' | 'closed' | 'unknown'
export type NoticeType = 'general' | 'no_rank' | 'resupply' | 'public_sale' | 'public_rent'

export interface SupplyTag {
  code: string
  households: number
}

export interface NoticeItem {
  house_manage_no: string
  name: string
  notice_type: NoticeType
  house_kind: 'private' | 'public' | null
  region: string
  district: string | null
  status: NoticeStatus
  d_day: number | null
  rcept_start: string | null
  rcept_end: string | null
  winner_on: string | null
  is_corrected: boolean
  price_min_man: number | null
  price_max_man: number | null
  area_min: number | null
  area_max: number | null
  total_households: number | null
  supply_tags: SupplyTag[]
  /** 공공 공고의 공급유형(행복주택 등). 공공분양은 null */
  supply_kind: string | null
  /** 공공 공고의 공급기관(LH 등) */
  provider: string | null
}

export interface Relaxation {
  drop: string
  total: number
}

export interface NoticeList {
  total: number
  offset: number
  items: NoticeItem[]
  no_info_excluded: number
  relaxations: Relaxation[]
  last_crawl: { finished_at: string | null; stale: boolean }
}

// ---- 상세 (API.md 3장) ----

export interface NoticeDetail {
  header: {
    house_manage_no: string
    name: string
    notice_type: NoticeType
    house_kind: 'private' | 'public' | null
    region: string
    address: string | null
    status: NoticeStatus
    d_day: number | null
    is_corrected: boolean
    correction_count: number
    last_checked_at: string | null
    removed: boolean
    notice_url: string
    homepage_url: string | null
    phone: string | null
    supply_kind: string | null
    provider: string | null
    source: 'applyhome' | 'myhome'
    /** 정정 전 공고 번호 */
    before_notice_no: string | null
    /** 이 공고를 대체한 정정 공고 번호 */
    successor_no: string | null
  }
  summary: {
    total_households: number | null
    special_households: number | null
    general_households: number | null
    price_min_man: number | null
    price_max_man: number | null
    move_in_ym: string | null
  }
  schedule: ScheduleRow[]
  units: Unit[]
  complexes: Complex[]
  extraction: Extraction
  changes: Change[]
}

export interface Complex {
  name: string | null
  address: string | null
  households: number | null
}

export interface ScheduleRow {
  stage: string
  area: string
  start_on: string
  end_on: string
  state: 'done' | 'today' | 'upcoming'
}

export interface Unit {
  model_no: string
  house_ty: string
  exclusive_area: number
  supply_area: number | null
  top_price_man: number | null
  general_count: number | null
  special_count: number | null
  special_by_type: Record<string, number | null> | null
}

export interface Evidence {
  page: number
  quote: string
  verified: boolean
}

export interface ExtractedItem {
  id: number
  category: number
  code: string | null
  targets: string[]
  text: string
  is_general_rule: boolean
  status: 'confirmed' | 'needs_review'
  evidence: Evidence[]
}

export interface Extraction {
  status: 'pending' | 'done' | 'partial' | 'failed'
  reprocessing: boolean
  constraints: { items: ExtractedItem[]; missing_codes: string[] }
  conditions: { supply_types: { code: string; households: number | null }[]; items: ExtractedItem[] }
}

export interface Change {
  version: number
  detected_at: string
  items: { field: string; before: string | null; after: string | null }[]
}
