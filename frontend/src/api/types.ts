// API.md 2장의 응답. 필드 이름은 서버와 같은 snake_case다.
export type NoticeStatus = 'upcoming' | 'open' | 'closed' | 'unknown'
export type NoticeType = 'general' | 'no_rank' | 'resupply'

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
