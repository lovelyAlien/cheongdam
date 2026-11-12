import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { fetchNoticeCalendar } from '../api/notices'
import type { CalendarEvent } from '../api/types'
import { activeFilterCount, parseFilters, toParams } from '../lib/filters'
import { STAGE_LABEL } from '../lib/format'
import { useFavorites } from '../lib/favorites'
import { useDesktop } from '../lib/useDesktop'
import { TopNav } from '../components/TopNav'

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토']
const pad = (n: number) => String(n).padStart(2, '0')
const ymd = (y: number, m: number, d: number) => `${y}-${pad(m)}-${pad(d)}`

/** 일정 종류 칩. stages가 null이면 모든 단계 */
const KINDS: { key: string; label: string; stages: string[] | null }[] = [
  { key: 'all', label: '전체 일정', stages: null },
  { key: 'apply', label: '청약 접수', stages: ['rank1', 'rank2', 'general'] },
  { key: 'special', label: '특별공급', stages: ['special'] },
  { key: 'no_rank', label: '무순위', stages: ['no_rank'] },
]
/** 데스크톱 날짜 칸에 단지명 칩을 몇 개까지 넣을지 */
const CELL_CHIPS = 2

const stageLabel = (s: string) => STAGE_LABEL[s] ?? s
/** 달력 칸·목록의 표시 단위. 'rank1'은 그날 하루짜리이거나 시작, 'rank1:end'는 마감 */
const markStage = (mark: string) => mark.split(':')[0]
const markLabel = (mark: string) => stageLabel(markStage(mark)) + (mark.endsWith(':end') ? ' 마감' : '')

function thisMonth(): string {
  const t = new Date()
  return `${t.getFullYear()}-${pad(t.getMonth() + 1)}`
}

function shiftMonth(month: string, delta: number): string {
  const [y, m] = month.split('-').map(Number)
  const d = new Date(y, m - 1 + delta, 1)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}`
}

type DayItem = { no: string; name: string; marks: string[] }

/**
 * 그 달의 날마다 일정이 있는 공고를 모은다. 기간이 있는 일정(계약 등)은 시작일과 마감일에만 넣는다.
 * 기간 내내 칠하면 상시·장기 모집이 모든 날을 채워 그날 할 일이 묻힌다.
 * 같은 공고의 표시는 한 줄로 묶는다(지역별 1·2순위가 여러 줄로 반복되지 않게). 키는 YYYY-MM-DD.
 */
function itemsByDay(events: CalendarEvent[], month: string): Map<string, DayItem[]> {
  const out = new Map<string, DayItem[]>()
  const add = (key: string, e: CalendarEvent, mark: string) => {
    if (!key.startsWith(month)) return
    const day = out.get(key) ?? []
    const item = day.find((i) => i.no === e.house_manage_no)
    if (!item) day.push({ no: e.house_manage_no, name: e.name, marks: [mark] })
    else if (!item.marks.includes(mark)) item.marks.push(mark)
    out.set(key, day)
  }
  for (const e of events) {
    add(e.start_on, e, e.stage)
    if (e.end_on !== e.start_on) add(e.end_on, e, `${e.stage}:end`)
  }
  return out
}

type Load = { key: string; events: CalendarEvent[] | null; error: boolean }

/**
 * 일정 캘린더(S-10, F2-8). 목록과 같은 필터를 URL로 받고, 관심 공고(D-32)와 일정 종류로 더 거른다.
 * 데스크톱은 날짜 칸에 단지명 칩, 오른쪽에 그날 목록. 모바일은 칸에 공고 수, 날짜를 누르면 바텀시트.
 */
export function CalendarPage() {
  const [params, setParams] = useSearchParams()
  const month = /^\d{4}-\d{2}$/.test(params.get('month') ?? '') ? params.get('month')! : thisMonth()
  const favOnly = params.get('fav') === '1'
  const kind = KINDS.find((k) => k.key === params.get('kind')) ?? KINDS[0]
  const filters = parseFilters(params)
  const filterQuery = toParams(filters)
  filterQuery.delete('status') // 달력이 기간을 정한다
  filterQuery.delete('sort')
  const key = `${month}?${filterQuery}`

  const [load, setLoad] = useState<Load | null>(null)
  const [retry, setRetry] = useState(0)
  const [picked, setPicked] = useState<string | null>(null)
  const [sheetOpen, setSheetOpen] = useState(false)
  const { ids } = useFavorites()
  const desktop = useDesktop()

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

  const sheetVisible = sheetOpen && !desktop
  useEffect(() => {
    if (!sheetVisible) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setSheetOpen(false)
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [sheetVisible])

  const current = load?.key === key ? load : null
  const events = useMemo(
    () =>
      (current?.events ?? []).filter(
        (e) => (!favOnly || ids.includes(e.house_manage_no)) && (!kind.stages || kind.stages.includes(e.stage)),
      ),
    [current, favOnly, ids, kind],
  )
  const byDay = useMemo(() => itemsByDay(events, month), [events, month])
  // 범례는 이 달 화면에 실제로 나온 단계만
  const legend = useMemo(
    () => Object.keys(STAGE_LABEL).filter((s) => events.some((e) => e.stage === s)),
    [events],
  )

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

  const go = (next: { month?: string; fav?: boolean; kind?: string }) => {
    const p = new URLSearchParams(params)
    if (next.month) p.set('month', next.month)
    if (next.fav !== undefined) {
      if (next.fav) p.set('fav', '1')
      else p.delete('fav')
    }
    if (next.kind !== undefined) {
      if (next.kind === 'all') p.delete('kind')
      else p.set('kind', next.kind)
    }
    setParams(p, { replace: true })
  }
  const count = activeFilterCount({ ...filters, status: ['upcoming', 'open'] })

  // 달 전체에 대한 상태. 있으면 그날 목록 대신 이것을 보여 준다
  const status = !current ? (
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
  ) : null

  const dayTitle = `${sm}월 ${sd}일 ${current?.events ? `공고 ${dayEvents.length}건` : ''}`
  const dayList =
    dayEvents.length === 0 ? (
      <p className="muted">이 날은 일정이 없어요.</p>
    ) : (
      <ul className="rows">
        {dayEvents.map((it) => (
          <li key={it.no} className="row">
            <Link className="cal-event plain" to={`/notices/${it.no}`}>
              <span className="cal-stages">
                {it.marks.map((mk) => <span key={mk} className={`badge stage st-${markStage(mk)}`}>{markLabel(mk)}</span>)}
              </span>
              <span className="cal-name">{it.name}</span>
            </Link>
          </li>
        ))}
      </ul>
    )

  return (
    <main className="list-page calendar-page">
      <TopNav query={toParams(filters).toString()} />
      <h1 className="page-title">일정 캘린더</h1>

      <div className="cal-controls">
        <Link className="btn small-btn plain" to={`/?${toParams(filters)}`}>
          {count > 0 ? `필터 ${count} · 목록에서 바꾸기` : '필터는 목록에서 고를 수 있어요'}
        </Link>
        <div className="segmented" role="group" aria-label="보기">
          <button type="button" aria-pressed={!favOnly} onClick={() => go({ fav: false })}>전체</button>
          <button type="button" aria-pressed={favOnly} onClick={() => go({ fav: true })}>관심 공고만</button>
        </div>
      </div>
      <div className="chips cal-kinds" role="group" aria-label="일정 종류">
        {KINDS.map((k) => (
          <button
            key={k.key}
            type="button"
            className={`chip small${k.key === kind.key ? ' on' : ''}`}
            aria-pressed={k.key === kind.key}
            onClick={() => go({ kind: k.key })}
          >
            {k.label}
          </button>
        ))}
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
              const items = byDay.get(key) ?? []
              const n = items.length
              return (
                <button
                  key={key}
                  type="button"
                  className={['cal-day', key === today && 'today', key === selected && 'sel'].filter(Boolean).join(' ')}
                  aria-pressed={key === selected}
                  aria-label={`${m}월 ${i + 1}일${n ? ` 공고 ${n}건` : ''}`}
                  onClick={() => { setPicked(key); setSheetOpen(true) }}
                >
                  <span className="day-num">{i + 1}</span>
                  {desktop ? (
                    <span className="day-chips" aria-hidden="true">
                      {items.slice(0, CELL_CHIPS).map((it) => (
                        <span key={it.no} className={`day-chip st-${markStage(it.marks[0])}`}>{it.name}</span>
                      ))}
                      {n > CELL_CHIPS && <span className="day-more">+{n - CELL_CHIPS}개</span>}
                    </span>
                  ) : (
                    <span className="day-count" aria-hidden="true">{n || ''}</span>
                  )}
                </button>
              )
            })}
          </div>
          {legend.length > 0 && (
            <ul className="cal-legend" aria-label="단계 색">
              {legend.map((s) => <li key={s} className={`st-${s}`}>{stageLabel(s)}</li>)}
            </ul>
          )}
        </section>

        {desktop ? (
          <section className="cal-list" aria-live="polite">
            <h2>{dayTitle}</h2>
            {status ?? dayList}
          </section>
        ) : (
          status && <section className="cal-list" aria-live="polite">{status}</section>
        )}
      </div>

      {sheetVisible && !status && (
        <div
          className="sheet-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label={dayTitle}
          onClick={(e) => e.target === e.currentTarget && setSheetOpen(false)}
        >
          <div className="sheet compact">
            <header className="sheet-head">
              <h2>{dayTitle}</h2>
              <button type="button" className="icon-btn" aria-label="닫기" onClick={() => setSheetOpen(false)} autoFocus>✕</button>
            </header>
            <div className="sheet-body cal-sheet">{dayList}</div>
          </div>
        </div>
      )}
    </main>
  )
}
