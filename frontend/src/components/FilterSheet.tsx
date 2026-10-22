import { useEffect, useState } from 'react'
import { fetchNotices } from '../api/notices'
import {
  AREA_OPTIONS,
  CAPITAL_AREA,
  HOUSE_KIND_OPTIONS,
  NOTICE_TYPE_OPTIONS,
  REGIONS,
  STATUS_OPTIONS,
  SUPPLY_OPTIONS,
  toParams,
  type Filters,
} from '../lib/filters'
import { TERM_NOTE, TERMS } from '../lib/terms'

const PRICE_STEPS = Array.from({ length: 16 }, (_, i) => i) // 0~15억

interface Props {
  initial: Filters
  onApply: (f: Filters) => void
  onClose?: () => void
  /** 데스크톱 고정 패널. 바꿀 때마다 바로 적용하고 시트 헤더·푸터는 그리지 않는다. */
  inline?: boolean
}

const DEFAULTS: Omit<Filters, 'q' | 'sort'> = {
  status: ['upcoming', 'open'], region: [], supply: [], price_min: null, price_max: null, area: [],
  notice_type: [], house_kind: [],
}

function toggle(list: string[], value: string): string[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value]
}

function Chips({
  options,
  selected,
  onChange,
  withHelp = false,
}: {
  options: { value: string; label: string }[]
  selected: string[]
  onChange: (next: string[]) => void
  withHelp?: boolean
}) {
  const [helpOf, setHelpOf] = useState<string | null>(null)
  const help = options.find((o) => o.value === helpOf)
  return (
    <>
      <div className="chips">
        {options.map((o) => (
          <span key={o.value} className="chip-wrap">
            <button
              type="button"
              className={selected.includes(o.value) ? 'chip on' : 'chip'}
              aria-pressed={selected.includes(o.value)}
              onClick={() => onChange(toggle(selected, o.value))}
            >
              {o.label}
            </button>
            {withHelp && TERMS[o.value] && (
              <button
                type="button"
                className="help-btn"
                aria-label={`${o.label} 설명`}
                aria-expanded={helpOf === o.value}
                onClick={() => setHelpOf(helpOf === o.value ? null : o.value)}
              >
                ?
              </button>
            )}
          </span>
        ))}
      </div>
      {help && (
        <p className="hint">
          <strong>{help.label}</strong> {TERMS[help.value]}. {TERM_NOTE}
        </p>
      )}
    </>
  )
}

/**
 * S-02. 모바일 시트는 조건을 바꿀 때마다 `공고 N건 보기`의 N을 갱신해, 0건이 되는 조합을 적용 전에 알 수 있게 한다.
 * 데스크톱 패널(inline)은 목록 결과 수가 곧 그 건수라 바로 적용한다.
 */
export function FilterSheet({ initial, onApply, onClose, inline = false }: Props) {
  const [sheetDraft, setDraft] = useState<Filters>(initial)
  const draft = inline ? initial : sheetDraft
  const [count, setCount] = useState<number | null>(null)

  useEffect(() => {
    if (inline) return
    const ctl = new AbortController()
    const params = toParams(draft)
    params.set('limit', '0')
    fetchNotices(params, ctl.signal)
      .then((r) => setCount(r.total))
      .catch(() => {})
    return () => ctl.abort()
  }, [draft, inline])

  const set = <K extends keyof Filters>(key: K, value: Filters[K]) =>
    inline ? onApply({ ...initial, [key]: value }) : setDraft((d) => ({ ...d, [key]: value }))
  const reset = () => (inline ? onApply({ ...initial, ...DEFAULTS }) : setDraft({ ...initial, ...DEFAULTS }))
  const capitalOn = CAPITAL_AREA.every((r) => draft.region.includes(r))

  const body = (
    <div className="sheet-body">
      <section>
        <h3>접수 상태</h3>
        <Chips options={STATUS_OPTIONS} selected={draft.status} onChange={(v) => set('status', v.length ? v : initial.status)} />
      </section>

      <section>
        <h3>지역</h3>
        <div className="chips">
          <button
            type="button"
            className={capitalOn ? 'chip on' : 'chip'}
            aria-pressed={capitalOn}
            onClick={() =>
              set('region', capitalOn ? draft.region.filter((r) => !CAPITAL_AREA.includes(r)) : [...new Set([...draft.region, ...CAPITAL_AREA])])
            }
          >
            수도권
          </button>
        </div>
        <Chips options={REGIONS.map((r) => ({ value: r, label: r }))} selected={draft.region} onChange={(v) => set('region', v)} />
      </section>

      <section>
        <h3>특별공급 유형</h3>
        <Chips options={SUPPLY_OPTIONS} selected={draft.supply} onChange={(v) => set('supply', v)} withHelp />
        <p className="hint">무순위·재공급 공고는 특별공급 정보가 없어 제외돼요.</p>
      </section>

      <section>
        <h3>분양가</h3>
        <div className="range">
          <select
            aria-label="최소 분양가"
            value={draft.price_min ?? ''}
            onChange={(e) => set('price_min', e.target.value === '' ? null : Number(e.target.value))}
          >
            <option value="">최소 없음</option>
            {PRICE_STEPS.slice(1).map((n) => (
              <option key={n} value={n * 10000}>{n}억</option>
            ))}
          </select>
          <span>~</span>
          <select
            aria-label="최대 분양가"
            value={draft.price_max ?? ''}
            onChange={(e) => set('price_max', e.target.value === '' ? null : Number(e.target.value))}
          >
            <option value="">15억 이상</option>
            {PRICE_STEPS.slice(1, 15).map((n) => (
              <option key={n} value={n * 10000}>{n}억</option>
            ))}
          </select>
        </div>
      </section>

      <section>
        <h3>전용면적</h3>
        <Chips options={AREA_OPTIONS} selected={draft.area} onChange={(v) => set('area', v)} />
      </section>

      <section>
        <h3>공고 유형</h3>
        <Chips options={NOTICE_TYPE_OPTIONS} selected={draft.notice_type} onChange={(v) => set('notice_type', v)} withHelp />
      </section>

      <section>
        <h3>주택 구분</h3>
        <Chips options={HOUSE_KIND_OPTIONS} selected={draft.house_kind} onChange={(v) => set('house_kind', v)} withHelp />
      </section>
    </div>
  )

  if (inline) {
    return (
      <aside className="filter-panel" aria-label="필터">
        <header className="sheet-head">
          <h2>필터</h2>
          <button type="button" className="btn ghost small-btn" onClick={reset}>초기화</button>
        </header>
        {body}
      </aside>
    )
  }

  return (
    <div className="sheet-backdrop" role="dialog" aria-modal="true" aria-label="필터">
      <div className="sheet">
        <header className="sheet-head">
          <h2>필터</h2>
          <button type="button" className="icon-btn" aria-label="닫기" onClick={onClose}>
            ✕
          </button>
        </header>

        {body}

        <footer className="sheet-foot">
          <button type="button" className="btn ghost" onClick={reset}>
            초기화
          </button>
          <button type="button" className="btn primary" onClick={() => onApply(draft)}>
            {count == null ? '공고 보기' : `공고 ${count}건 보기`}
          </button>
        </footer>
      </div>
    </div>
  )
}
