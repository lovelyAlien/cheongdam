import { Link } from 'react-router-dom'
import type { NoticeItem } from '../api/types'
import { labelOf, SUPPLY_OPTIONS } from '../lib/filters'
import {
  formatAreaRange,
  formatDate,
  formatHouseholds,
  formatPriceRange,
} from '../lib/format'

const MAX_TAGS = 3

function statusBadge(n: NoticeItem): { text: string; cls: string } {
  switch (n.status) {
    case 'open':
      return { text: n.d_day === 0 ? '접수 중 · 오늘 마감' : `접수 중 D-${n.d_day}`, cls: 'open' }
    case 'upcoming':
      return { text: `접수 예정 D-${n.d_day}`, cls: 'upcoming' }
    case 'closed':
      return { text: '마감', cls: 'closed' }
    default:
      return { text: '일정 확인 필요', cls: 'unknown' }
  }
}

function scheduleLine(n: NoticeItem): string | null {
  if (n.status === 'closed') {
    return n.winner_on ? `당첨자 발표 ${formatDate(n.winner_on)}` : null
  }
  if (n.status === 'unknown' || !n.rcept_start || !n.rcept_end) return null
  return n.rcept_start === n.rcept_end
    ? `접수 ${formatDate(n.rcept_start)}`
    : `접수 ${formatDate(n.rcept_start)} → ${formatDate(n.rcept_end)}`
}

export function NoticeCard({
  notice: n,
  highlight,
  detailQuery = '',
}: {
  notice: NoticeItem
  highlight: string[]
  /** 상세로 함께 넘기는 주택형 단위 필터(toDetailParams) */
  detailQuery?: string
}) {
  const badge = statusBadge(n)
  const facts = [
    formatPriceRange(n.price_min_man, n.price_max_man),
    formatAreaRange(n.area_min, n.area_max),
    formatHouseholds(n.total_households),
  ].filter(Boolean)
  const tags = n.supply_tags.slice(0, MAX_TAGS)
  const more = n.supply_tags.length - tags.length
  const schedule = scheduleLine(n)

  return (
    <li className="card">
      <Link to={`/notices/${n.house_manage_no}${detailQuery ? `?${detailQuery}` : ''}`} className="card-link">
        <div className="badges">
          <span className={`badge ${badge.cls}`}>{badge.text}</span>
          {n.notice_type === 'no_rank' && <span className="badge type">무순위</span>}
          {n.notice_type === 'resupply' && <span className="badge type">재공급</span>}
          {n.is_corrected && <span className="badge corrected">정정</span>}
        </div>
        <div className="card-title">
          <h2>{n.name}</h2>
          {n.house_kind && <span className="kind">{n.house_kind === 'private' ? '민영' : '국민'}</span>}
        </div>
        <p className="location">{[n.region, n.district].filter(Boolean).join(' ')}</p>
        <p className="facts">{facts.join(' · ')}</p>
        {schedule && <p className="schedule">{schedule}</p>}
        {tags.length > 0 && (
          <p className="tags">
            {tags.map((t) => (
              <span key={t.code} className={highlight.includes(t.code) ? 'tag hit' : 'tag'}>
                #{labelOf(SUPPLY_OPTIONS, t.code)}
              </span>
            ))}
            {more > 0 && <span className="tag">+{more}</span>}
          </p>
        )}
      </Link>
    </li>
  )
}
