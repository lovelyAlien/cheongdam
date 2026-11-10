import { Link } from 'react-router-dom'
import type { NoticeItem } from '../api/types'
import { labelOf, SUPPLY_OPTIONS } from '../lib/filters'
import { formatDate, formatPriceRange } from '../lib/format'
import { FavoriteButton } from './FavoriteButton'

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

/** 카드는 한 줄에 분양가와 함께 쓰므로 요일을 뺀다: 10.20 / 2027.01.05 */
const shortDate = (iso: string) => formatDate(iso).replace(/\(.\)$/, '')

function scheduleLine(n: NoticeItem): string | null {
  if (n.status === 'closed') {
    return n.winner_on ? `당첨자 발표 ${formatDate(n.winner_on)}` : null
  }
  if (n.status === 'unknown' || !n.rcept_start || !n.rcept_end) return null
  return n.rcept_start === n.rcept_end
    ? `접수 ${formatDate(n.rcept_start)}`
    : `접수 ${shortDate(n.rcept_start)} → ${shortDate(n.rcept_end)}`
}

/** 목록 카드(S-01, D-33): 배지 · 단지명 · 지역·구분 · 분양가·일정 4줄. 나머지는 상세에서 본다. */
export function NoticeCard({
  notice: n,
  highlight,
  detailQuery = '',
  selected = false,
}: {
  notice: NoticeItem
  /** 필터에서 고른 특별공급 유형. 이 공고에 있는 것만 태그로 보여 준다 */
  highlight: string[]
  /** 상세로 함께 넘기는 주택형 단위 필터(toDetailParams) */
  detailQuery?: string
  /** 지도에서 고른 공고(데스크톱 목록 강조) */
  selected?: boolean
}) {
  const badge = statusBadge(n)
  const isPublic = n.notice_type === 'public_sale' || n.notice_type === 'public_rent'
  const where = [
    [n.region, n.district].filter(Boolean).join(' '),
    isPublic ? n.supply_kind : n.house_kind === 'private' ? '민영' : n.house_kind === 'public' ? '국민' : null,
    isPublic ? n.provider : null,
  ]
    .filter(Boolean)
    .join(' · ')
  // 공공 공고는 분양가가 없는 것이 정상이라 `공고문 확인`을 쓰지 않는다(D-30)
  const line = [isPublic ? null : formatPriceRange(n.price_min_man, n.price_max_man), scheduleLine(n)]
    .filter(Boolean)
    .join(' · ')
  const tags = n.supply_tags.filter((t) => highlight.includes(t.code))

  return (
    <li className={selected ? 'card selected' : 'card'} data-no={n.house_manage_no}>
      <Link to={`/notices/${n.house_manage_no}${detailQuery ? `?${detailQuery}` : ''}`} className="card-link">
        <div className="badges">
          <span className={`badge ${badge.cls}`}>{badge.text}</span>
          {n.notice_type === 'no_rank' && <span className="badge type">무순위</span>}
          {n.notice_type === 'resupply' && <span className="badge type">재공급</span>}
          {n.notice_type === 'public_sale' && <span className="badge type">공공분양</span>}
          {n.notice_type === 'public_rent' && <span className="badge type">공공임대</span>}
          {n.is_corrected && <span className="badge corrected">정정</span>}
        </div>
        <h2 className="card-name">{n.name}</h2>
        {where && <p className="location">{where}</p>}
        {line && <p className="facts">{line}</p>}
        {tags.length > 0 && (
          <p className="tags">
            {tags.map((t) => (
              <span key={t.code} className="tag hit">#{labelOf(SUPPLY_OPTIONS, t.code)}</span>
            ))}
          </p>
        )}
      </Link>
      <FavoriteButton no={n.house_manage_no} name={n.name} />
    </li>
  )
}
