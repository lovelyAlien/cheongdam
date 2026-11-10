import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { fetchNoticeCalendar } from '../api/notices'
import type { CalendarEvent } from '../api/types'
import { activeFilterCount, parseFilters, toParams } from '../lib/filters'
import { STAGE_LABEL } from '../lib/format'
import { useFavorites } from '../lib/favorites'

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토']
const pad = (n: number) => String(n).padStart(2, '0')
const ymd = (y: number, m: number, d: number) => `${y}-${pad(m)}-${pad(d)}`

function thisMonth(): string {
  const t = new Date()
  return `${t.getFullYear()}-${pad(t.getMonth() + 1)}`
}

function shiftMonth(month: string, delta: number): string {
  const [y, m] = month.split('-').map(Number)
  const d = new Date(y, m - 1 + delta, 1)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}`
}

type DayItem = { no: string; name: string; stages: string[] }

/**
 * 그 달의 날마다 일정이 있는 공고를 모은다. 기간이 있는 일정(계약 등)은 기간 안의 날마다 넣고,
 * 같은 공고의 단계는 한 줄로 묶는다(지역별 1·2순위가 여러 줄로 반복되지 않게). 키는 YYYY-MM-DD.
 */
function itemsByDay(events: CalendarEvent[], month: string): Map<string, DayItem[]> {
  const [y, m] = month.split('-').map(Number)
  const last = new Date(y, m, 0).getDate()
  const out = new Map<string, DayItem[]>()
  for (const e of events) {
    const label = STAGE_LABEL[e.stage] ?? e.stage
    for (let d = 1; d <= last; d++) {
      const key = ymd(y, m, d)
      if (key < e.start_on || key > e.end_on) continue
      const day = out.get(key) ?? []
      const item = day.find((i) => i.no === e.house_manage_no)
      if (!item) day.push({ no: e.house_manage_no, name: e.name, stages: [label] })
      else if (!item.stages.includes(label)) item.stages.push(label)
      out.set(key, day)
    }
  }
  return out
}

type Load = { key: string; events: CalendarEvent[] | null; error: boolean }

/** 일정 캘린더(S-10, F2-8). 목록과 같은 필터를 URL로 받고, 관심 공고(D-32)만 볼 수 있다. */
export function CalendarPage() {
  const [params, setParams] = useSearchParams()
  const month = /^\d{4}-\d{2}$/.test(params.get('month') ?? '') ? params.get('month')! : thisMonth()
  const favOnly = params.get('fav') === '1'
  const filters = parseFilters(params)
  const filterQuery = toParams(filters)
  filterQuery.delete('status') // 달력이 기간을 정한다
  filterQuery.delete('sort')
  const key = `${month}?${filterQuery}`

  const [load, setLoad] = useState<Load | null>(null)
  const [retry, setRetry] = useState(0)
  const [picked, setPicked] = useState<string | null>(null)
  const { ids } = useFavorites()

  useEffect(() => {
    const ctl = new AbortController()
    const q = new URLSearchParams(filterQuery)
    q.set('month', month)
    fetchNoticeCalendar(q, ctl.signal)
      .then((d) => setLoad({ key, events: d.events, error: false }))
      .catch((e: unknown) => {
        if ((e as Error).name !== 'AbortError') setLoad({ key, events: null, error: true })
      })
    return () => ctl.abort()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- key가 month와 필터 쿼리를 모두 담는다
  }, [key, retry])

  const current = load?.key === key ? load : null
  const events = useMemo(
    () => (current?.events ?? []).filter((e) => !favOnly || ids.includes(e.house_manage_no)),
    [current, favOnly, ids],
  )
  const byDay = useMemo(() => itemsByDay(events, month), [events, month])

  const [y, m] = month.split('-').map(Number)
  const today = (() => {
    const t = new Date()
    return ymd(t.getFullYear(), t.getMonth() + 1, t.getDate())
  })()
  const firstDow = new Date(y, m - 1, 1).getDay()
  const days = new Date(y, m, 0).getDate()
  // 고른 날이 이 달이 아니면 오늘(이 달일 때) 또는 1일
  const selected = picked?.startsWith(month) ? picked : today.startsWith(month) ? today : ymd(y, m, 1)
  const dayEvents = byDay.get(selected) ?? []
  const [, sm, sd] = selected.split('-').map(Number)

  const go = (next: { month?: string; fav?: boolean }) => {
    const p = new URLSearchParams(params)
    if (next.month) p.set('month', next.month)
    if (next.fav !== undefined) {
      if (next.fav) p.set('fav', '1')
      else p.delete('fav')
    }
    setParams(p, { replace: true })
  }
  const count = activeFilterCount({ ...filters, status: ['upcoming', 'open'] })

  return (
    <main className="list-page calendar-page">
      <div className="detail-top">
        <Link className="icon-btn plain" to={`/?${toParams(filters)}`} aria-label="공고 목록으로">←</Link>
        <h1 className="page-title">일정 캘린더</h1>
        <span />
      </div>

      <div className="cal-controls">
        <Link className="btn small-btn plain" to={`/?${toParams(filters)}`}>
          {count > 0 ? `필터 ${count} · 목록에서 바꾸기` : '필터는 목록에서 고를 수 있어요'}
        </Link>
        <div className="segmented" role="group" aria-label="보기">
          <button type="button" aria-pressed={!favOnly} onClick={() => go({ fav: false })}>전체</button>
          <button type="button" aria-pressed={favOnly} onClick={() => go({ fav: true })}>관심 공고만</button>
        </div>
      </div>

      <div className="cal-layout">
        <section className="cal" aria-label={`${y}년 ${m}월`}>
          <div className="cal-head">
            <button type="button" className="icon-btn" aria-label="이전 달" onClick={() => go({ month: shiftMonth(month, -1) })}>‹</button>
            <strong>{y}년 {m}월</strong>
            <button type="button" className="icon-btn" aria-label="다음 달" onClick={() => go({ month: shiftMonth(month, 1) })}>›</button>
            {month !== thisMonth() && (
              <button type="button" className="btn small-btn" onClick={() => { setPicked(null); go({ month: thisMonth() }) }}>오늘</button>
            )}
          </div>
          <div className="cal-grid">
            {WEEKDAYS.map((w) => <span key={w} className="cal-dow">{w}</span>)}
            {Array.from({ length: firstDow }, (_, i) => <span key={`b${i}`} />)}
            {Array.from({ length: days }, (_, i) => {
              const key = ymd(y, m, i + 1)
              const n = byDay.get(key)?.length ?? 0
              return (
                <button
                  key={key}
                  type="button"
                  className={['cal-day', key === today && 'today', key === selected && 'sel'].filter(Boolean).join(' ')}
                  aria-pressed={key === selected}
                  aria-label={`${m}월 ${i + 1}일${n ? ` 공고 ${n}건` : ''}`}
                  onClick={() => setPicked(key)}
                >
                  <span>{i + 1}</span>
                  <span className="day-count" aria-hidden="true">{n || ''}</span>
                </button>
              )
            })}
          </div>
        </section>

        <section className="cal-list" aria-live="polite">
          <h2>{sm}월 {sd}일 {current?.events ? `공고 ${dayEvents.length}건` : ''}</h2>
          {!current ? (
            <p className="muted">불러오는 중이에요.</p>
          ) : current.error ? (
            <div className="state">
              <p>일정을 불러오지 못했어요.</p>
              <button type="button" className="btn" onClick={() => setRetry((r) => r + 1)}>다시 시도</button>
            </div>
          ) : favOnly && ids.length === 0 ? (
            <p className="muted">관심 공고가 아직 없어요. 목록이나 상세에서 ♡를 눌러 보세요.</p>
          ) : events.length === 0 ? (
            <p className="muted">이 달에는 조건에 맞는 일정이 없어요.</p>
          ) : dayEvents.length === 0 ? (
            <p className="muted">이 날은 일정이 없어요.</p>
          ) : (
            <ul className="rows">
              {dayEvents.map((it) => (
                <li key={it.no} className="row">
                  <Link className="cal-event plain" to={`/notices/${it.no}`}>
                    <span className="cal-name">{it.name}</span>
                    <span className="cal-stages">
                      {it.stages.map((st) => <span key={st} className="badge stage">{st}</span>)}
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </main>
  )
}
