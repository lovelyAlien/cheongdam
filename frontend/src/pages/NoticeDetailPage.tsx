import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { fetchNotice, NotFoundError } from '../api/notices'
import type { NoticeDetail, ScheduleRow, Unit } from '../api/types'
import { ExtractionBlocks } from '../components/detail/ExtractionBlocks'
import {
  AREA_LABEL,
  formatDateRange,
  formatDateTime,
  formatHouseholds,
  formatMoveIn,
  formatPriceMan,
  formatUnit,
  STAGE_LABEL,
} from '../lib/format'
import { SUPPLY_TYPE_LABEL } from '../lib/labels'

type Load = { no: string; detail: NoticeDetail | null; error: 'notfound' | 'error' | null }

export function NoticeDetailPage() {
  const { no = '' } = useParams()
  const navigate = useNavigate()
  const [load, setLoad] = useState<Load | null>(null)
  const [retry, setRetry] = useState(0)
  const [toast, setToast] = useState<string | null>(null)

  useEffect(() => {
    const ctl = new AbortController()
    fetchNotice(no, ctl.signal)
      .then((detail) => setLoad({ no, detail, error: null }))
      .catch((e: unknown) => {
        if ((e as Error).name === 'AbortError') return
        setLoad({ no, detail: null, error: e instanceof NotFoundError ? 'notfound' : 'error' })
      })
    return () => ctl.abort()
  }, [no, retry])

  const current = load && load.no === no ? load : null
  const detail = current?.detail ?? null

  const back = () => (window.history.length > 1 ? navigate(-1) : navigate('/'))

  const share = async () => {
    const url = window.location.href
    if (navigator.share) {
      try {
        await navigator.share({ title: detail?.header.name, url })
      } catch {
        /* 사용자가 취소 */
      }
      return
    }
    await navigator.clipboard.writeText(url)
    setToast('링크를 복사했어요')
    setTimeout(() => setToast(null), 2000)
  }

  if (!current) {
    return <main className="list-page"><p className="state">불러오는 중이에요.</p></main>
  }
  if (current.error === 'notfound') {
    return (
      <main className="list-page">
        <p className="state">찾는 페이지가 없어요.</p>
        <p className="state"><Link to="/">공고 목록으로</Link></p>
      </main>
    )
  }
  if (!detail) {
    return (
      <main className="list-page">
        <div className="state">
          <p>잠시 문제가 생겼어요.</p>
          <button type="button" className="btn" onClick={() => { setLoad(null); setRetry((n) => n + 1) }}>다시 시도</button>
        </div>
      </main>
    )
  }

  const { header: h, summary: s } = detail
  const hasUpcoming = detail.schedule.some((r) => r.state !== 'done')

  return (
    <main className="list-page detail">
      <div className="detail-top">
        <button type="button" className="icon-btn" aria-label="뒤로" onClick={back}>←</button>
        <button type="button" className="btn small-btn" onClick={share}>공유</button>
      </div>

      <p className="disclaimer">공고문을 자동으로 정리한 참고 정보예요. 신청 전 반드시 공고문 원문을 확인하세요.</p>

      {h.removed && (
        <p className="banner">청약홈에서 내려간 공고예요. 정보가 정확하지 않을 수 있어요.</p>
      )}

      <header className="detail-head">
        <div className="badges">
          <StatusBadge status={h.status} dDay={h.d_day} />
          {h.notice_type === 'no_rank' && <span className="badge type">무순위</span>}
          {h.notice_type === 'resupply' && <span className="badge type">재공급</span>}
          {h.is_corrected && <span className="badge corrected">정정</span>}
        </div>
        <h1>{h.name}</h1>
        <p className="muted">
          {[h.house_kind === 'private' ? '민영' : h.house_kind === 'public' ? '국민' : null, h.address ?? h.region]
            .filter(Boolean)
            .join(' · ')}
        </p>
        <p className="muted small">
          {h.last_checked_at && `마지막 확인 ${formatDateTime(h.last_checked_at)}`}
          {h.correction_count > 0 && (
            <>
              {' · '}
              <a href="#changes">정정 {h.correction_count}회</a>
            </>
          )}
        </p>
      </header>

      <nav className="anchors" aria-label="섹션 이동">
        <a href="#schedule">일정</a>
        <a href="#constraints">당첨되면</a>
        <a href="#conditions">자격 조건</a>
        <a href="#units">주택형</a>
      </nav>

      <section id="schedule" className="block">
        <h2>핵심 요약 <span className="source">출처: 청약홈</span></h2>
        <dl className="facts-list">
          <Fact label="총 세대수">
            {s.total_households != null
              ? `${formatHouseholds(s.total_households)}${s.special_households != null && s.general_households != null ? ` (특별 ${s.special_households.toLocaleString('ko-KR')} · 일반 ${s.general_households.toLocaleString('ko-KR')})` : ''}`
              : null}
          </Fact>
          <Fact label="분양가">
            {s.price_min_man != null && s.price_max_man != null
              ? s.price_min_man === s.price_max_man
                ? formatPriceMan(s.price_min_man)
                : `${formatPriceMan(s.price_min_man)} ~ ${formatPriceMan(s.price_max_man)}`
              : null}
          </Fact>
          <Fact label="입주 예정">{formatMoveIn(s.move_in_ym)}</Fact>
        </dl>

        <h3>일정</h3>
        <Timeline rows={detail.schedule} />
        {hasUpcoming && (
          <a className="btn" href={`/api/notices/${h.house_manage_no}/calendar.ics`}>📅 캘린더에 추가</a>
        )}
      </section>

      <ExtractionBlocks extraction={detail.extraction} noticeUrl={h.notice_url} noticeType={h.notice_type} />

      <section id="units" className="block">
        <h2>주택형별 공급 <span className="source">출처: 청약홈</span></h2>
        <Units units={detail.units} />
      </section>

      {detail.changes.length > 0 && (
        <section id="changes" className="block">
          <h2>정정 이력</h2>
          <ul className="rows">
            {detail.changes.map((c) => (
              <li key={c.version} className="row">
                <p className="muted small">{formatDateTime(c.detected_at)}</p>
                {c.items.length === 0 ? (
                  <p>공고문이 새로 올라왔어요. 내용을 다시 정리했어요.</p>
                ) : (
                  c.items.map((i, idx) => (
                    <p key={idx}>
                      <strong>{i.field}</strong> {i.before ?? '-'} → {i.after ?? '-'}
                    </p>
                  ))
                )}
              </li>
            ))}
          </ul>
        </section>
      )}

      <div className="actions">
        <a className="btn primary" href={h.notice_url} target="_blank" rel="noreferrer">청약홈에서 보기 ↗</a>
      </div>

      <footer className="footer">
        청담은 청약홈(한국부동산원)의 공개 데이터와 공고문을 바탕으로 정보를 제공하며, 정보의 정확성을 보증하지 않습니다.
      </footer>
      {toast && <div className="toast" role="status">{toast}</div>}
    </main>
  )
}

function StatusBadge({ status, dDay }: { status: string; dDay: number | null }) {
  if (status === 'open') return <span className="badge open">{dDay === 0 ? '접수 중 · 오늘 마감' : `접수 중 D-${dDay}`}</span>
  if (status === 'upcoming') return <span className="badge upcoming">접수 예정 D-{dDay}</span>
  if (status === 'closed') return <span className="badge closed">마감</span>
  return <span className="badge unknown">일정 확인 필요</span>
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="fact">
      <dt>{label}</dt>
      <dd>{children ?? '공고문 확인'}</dd>
    </div>
  )
}

function Timeline({ rows }: { rows: ScheduleRow[] }) {
  if (rows.length === 0) return <p className="muted">일정 확인 필요. 청약홈에서 확인해 주세요.</p>
  return (
    <ol className="timeline">
      {rows.map((r) => (
        <li key={`${r.stage}-${r.area}`} className={`step ${r.state}`}>
          <span className="dot" aria-hidden="true">{r.state === 'done' ? '●' : r.state === 'today' ? '◉' : '○'}</span>
          <span className="step-name">
            {STAGE_LABEL[r.stage] ?? r.stage}
            {AREA_LABEL[r.area] ? ` (${AREA_LABEL[r.area]})` : ''}
          </span>
          <span className="step-date">{formatDateRange(r.start_on, r.end_on)}</span>
          <span className="step-state">{r.state === 'done' ? '완료' : r.state === 'today' ? '오늘' : ''}</span>
        </li>
      ))}
    </ol>
  )
}

function Units({ units }: { units: Unit[] }) {
  if (units.length === 0) return <p className="muted">주택형 정보가 없어요. 공고문에서 확인해 주세요.</p>
  return (
    <ul className="rows">
      {units.map((u) => (
        <li key={u.model_no} className="row">
          <div className="row-head">
            <strong>{formatUnit(u.exclusive_area, u.house_ty)}</strong>
            <span>{u.top_price_man != null ? formatPriceMan(u.top_price_man) : '공고문 확인'}</span>
          </div>
          <p className="muted">
            일반 {u.general_count ?? '-'}
            {u.special_count != null ? ` · 특별 ${u.special_count}` : ''}
          </p>
          {u.special_by_type == null ? (
            <p className="muted small">이 공고 유형은 청약홈에서 이 정보를 제공하지 않아요. 공고문에서 확인해 주세요.</p>
          ) : (
            <details>
              <summary>특별공급 유형별</summary>
              <ul className="by-type">
                {Object.entries(u.special_by_type)
                  .filter(([, n]) => n != null && n > 0)
                  .map(([code, n]) => (
                    <li key={code}>{SUPPLY_TYPE_LABEL[code] ?? code} {n}</li>
                  ))}
              </ul>
            </details>
          )}
        </li>
      ))}
    </ul>
  )
}
