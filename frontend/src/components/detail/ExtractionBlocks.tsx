import { useState } from 'react'
import type { Extraction, ExtractedItem, ScheduleRow } from '../../api/types'
import { formatDateRange } from '../../lib/format'
import { CONSTRAINT_LABEL, SUPPLY_TYPE_LABEL } from '../../lib/labels'

/** 근거 칩. 쪽 번호가 보이고, 눌러서 원문 인용문을 펼친다(원문 뷰어 S-06 전까지의 대안, 컷 순서 1번). */
function EvidenceChip({ item, open, onToggle }: { item: ExtractedItem; open: boolean; onToggle: () => void }) {
  const first = item.evidence[0]
  if (!first) return null
  const more = item.evidence.length - 1
  return (
    <button type="button" className="evidence" aria-expanded={open} onClick={onToggle}>
      p.{first.page}
      {more > 0 ? ` 외 ${more}` : ''}
    </button>
  )
}

function EvidenceQuotes({ item }: { item: ExtractedItem }) {
  return (
    <ul className="quotes">
      {item.evidence.map((e, i) => (
        <li key={i}>
          <span className="quote-page">p.{e.page}</span> {e.quote}
          {!e.verified && <span className="review-label"> · 원문과 일치하는지 확인하지 못했어요</span>}
        </li>
      ))}
    </ul>
  )
}

/** 근거 칩 + 펼침. 항목마다 펼침 상태를 따로 가진다. */
function useEvidence() {
  const [open, setOpen] = useState(false)
  return { open, toggle: () => setOpen((o) => !o) }
}

const PENDING = '공고문을 읽고 정리하고 있어요. 보통 몇 시간 안에 준비돼요.'
const FAILED = '이 공고는 자동 정리를 제공하지 못했어요. 공고문을 직접 확인해 주세요.'

interface Props {
  extraction: Extraction
  noticeUrl: string
  noticeType: string
  /** 목록 필터에서 고른 특별공급 유형. 이 공고에 있으면 블록 C의 기본 유형이 된다(F5-1) */
  preferredTypes: string[]
  /** 블록 A의 계약일. 계약금 항목 옆에 함께 보여 준다(F8-1) */
  contract: ScheduleRow | null
}

export function ExtractionBlocks({ extraction, noticeUrl, noticeType, preferredTypes, contract }: Props) {
  if (extraction.status === 'pending' || extraction.status === 'failed') {
    return (
      <section id="constraints" className="block">
        <h2>당첨되면 이렇게 돼요 · 자격 조건</h2>
        <p className="muted">{extraction.status === 'pending' ? PENDING : FAILED}</p>
        <a className="btn" href={noticeUrl} target="_blank" rel="noreferrer">청약홈에서 보기 ↗</a>
        <span id="conditions" />
      </section>
    )
  }
  return (
    <>
      {extraction.reprocessing && (
        <p className="banner">
          공고가 정정되어 내용을 다시 정리하고 있어요. 지금 보이는 내용은 정정 전 기준이에요.
        </p>
      )}
      <Constraints extraction={extraction} contract={contract} />
      <Conditions extraction={extraction} noticeType={noticeType} preferredTypes={preferredTypes} />
    </>
  )
}

const ALL = '전체'
const isAll = (targets: string[]) => targets.length === 0 || targets.includes(ALL)

function targetLabel(targets: string[]): string {
  return targets.map((t) => SUPPLY_TYPE_LABEL[t] ?? t).join('·')
}

/** 같은 항목이 공급 유형마다 값이 다르면 항목 이름 아래에 유형별로 나눠 보여 준다(F8-1). */
function groupByCode(items: ExtractedItem[]): { code: string; items: ExtractedItem[] }[] {
  const groups: { code: string; items: ExtractedItem[] }[] = []
  for (const i of items) {
    const code = i.code ?? ''
    const g = groups.find((x) => x.code === code)
    if (g) g.items.push(i)
    else groups.push({ code, items: [i] })
  }
  return groups
}

function Constraints({ extraction, contract }: { extraction: Extraction; contract: ScheduleRow | null }) {
  const { items, missing_codes: missing } = extraction.constraints
  return (
    <section id="constraints" className="block">
      <h2>당첨되면 이렇게 돼요</h2>
      {items.length === 0 ? (
        <p className="muted">이 공고문에서 당첨 후 제약을 찾지 못했어요. 공고문의 유의사항을 직접 확인해 주세요.</p>
      ) : (
        <ul className="rows">
          {groupByCode(items).map((g) => (
            <li key={g.code} className="row">
              <strong>{CONSTRAINT_LABEL[g.code] ?? g.code}</strong>
              {g.items.map((i) => (
                <ConstraintValue
                  key={i.id}
                  item={i}
                  showTargets={g.items.length > 1 || !isAll(i.targets)}
                  contract={g.code === 'payment' ? contract : null}
                />
              ))}
            </li>
          ))}
        </ul>
      )}
      {items.length > 0 && missing.length > 0 && (
        <p className="muted small">
          이 공고문에서 찾지 못한 항목: {missing.map((c) => CONSTRAINT_LABEL[c] ?? c).join(', ')}. 공고문의 유의사항에서 확인해 주세요.
        </p>
      )}
    </section>
  )
}

function ConstraintValue({
  item,
  showTargets,
  contract,
}: {
  item: ExtractedItem
  showTargets: boolean
  contract: ScheduleRow | null
}) {
  const ev = useEvidence()
  return (
    <div className={item.status === 'needs_review' ? 'value review' : 'value'}>
      <p>
        {showTargets && <span className="target">{isAll(item.targets) ? '모든 유형' : targetLabel(item.targets)}</span>}
        {item.text} <EvidenceChip item={item} open={ev.open} onToggle={ev.toggle} />
      </p>
      {contract && (
        <p className="muted small">계약일 {formatDateRange(contract.start_on, contract.end_on)}</p>
      )}
      {item.status === 'needs_review' && <span className="review-label">원문 확인 필요</span>}
      {ev.open && <EvidenceQuotes item={item} />}
    </div>
  )
}

function Conditions({
  extraction,
  noticeType,
  preferredTypes,
}: {
  extraction: Extraction
  noticeType: string
  preferredTypes: string[]
}) {
  const { supply_types: types, items } = extraction.conditions
  // 기본 유형: 목록 필터에서 고른 유형이 이 공고에 있으면 그중 세대수가 많은 것, 없으면 첫 칩(일반 1순위)
  const preferred = types
    .filter((t) => preferredTypes.includes(t.code))
    .sort((a, b) => (b.households ?? 0) - (a.households ?? 0))[0]
  const [selected, setSelected] = useState(preferred?.code ?? types[0]?.code ?? '')
  // 체크 상태는 브라우저 메모리에만 둔다. 서버, localStorage, sessionStorage에 쓰지 않는다(P-09, D-18).
  const [checked, setChecked] = useState<Record<string, number[]>>({})

  const forType = items.filter((i) => i.targets.includes(ALL) || i.targets.includes(selected))
  const rules = forType.filter((i) => i.is_general_rule)
  const conditions = forType.filter((i) => !i.is_general_rule)
  const checkable = conditions.filter((i) => i.status === 'confirmed')
  const done = checked[selected] ?? []

  const toggle = (id: number) =>
    setChecked((cur) => {
      const list = cur[selected] ?? []
      return { ...cur, [selected]: list.includes(id) ? list.filter((x) => x !== id) : [...list, id] }
    })

  return (
    <section id="conditions" className="block">
      <h2>자격 조건</h2>
      <p className="muted small">체크는 스스로 확인하기 위한 메모예요. 실제 자격은 공고문과 사업주체의 서류 심사로 정해져요.</p>
      <p className="muted small">체크한 내용은 저장되지 않아요.</p>

      {types.length > 1 && (
        <div className="chips type-chips" role="tablist" aria-label="공급 유형">
          {types.map((t) => (
            <button
              key={t.code}
              type="button"
              role="tab"
              aria-selected={selected === t.code}
              className={selected === t.code ? 'chip on' : 'chip'}
              onClick={() => setSelected(t.code)}
            >
              {SUPPLY_TYPE_LABEL[t.code] ?? t.code}
              {t.households != null && t.households > 0 ? ` ${t.households}` : ''}
            </button>
          ))}
        </div>
      )}
      {types.length === 1 && noticeType !== 'general' && (
        <p className="muted small">{SUPPLY_TYPE_LABEL[types[0].code] ?? types[0].code}</p>
      )}

      {conditions.length === 0 ? (
        <p className="muted">이 유형의 조건을 찾지 못했어요. 공고문에서 확인해 주세요.</p>
      ) : (
        <ul className="rows">
          {conditions.map((i) => (
            <ConditionRow key={i.id} item={i} checked={done.includes(i.id)} onToggle={() => toggle(i.id)} />
          ))}
        </ul>
      )}
      {checkable.length > 0 && <p className="muted small">{checkable.length}개 중 {done.length}개 체크</p>}

      {rules.length > 0 && (
        <details className="rules">
          <summary>일반 규정 ({rules.length})</summary>
          <ul className="rows">
            {rules.map((i) => (
              <RuleRow key={i.id} item={i} />
            ))}
          </ul>
        </details>
      )}
      {extraction.status === 'partial' && (
        <p className="muted small">일부 항목만 정리했어요. 나머지는 공고문에서 확인해 주세요.</p>
      )}
    </section>
  )
}

function ConditionRow({ item, checked, onToggle }: { item: ExtractedItem; checked: boolean; onToggle: () => void }) {
  const ev = useEvidence()
  return (
    <li className={item.status === 'needs_review' ? 'row review' : 'row'}>
      <label className="check">
        {item.status === 'confirmed' ? (
          <input type="checkbox" checked={checked} onChange={onToggle} />
        ) : (
          <span className="check-spacer" />
        )}
        <span>{item.text}</span>
      </label>
      <div className="row-head">
        {item.status === 'needs_review' ? <span className="review-label">원문 확인 필요</span> : <span />}
        <EvidenceChip item={item} open={ev.open} onToggle={ev.toggle} />
      </div>
      {ev.open && <EvidenceQuotes item={item} />}
    </li>
  )
}

function RuleRow({ item }: { item: ExtractedItem }) {
  const ev = useEvidence()
  return (
    <li className="row">
      <p>{item.text}</p>
      <EvidenceChip item={item} open={ev.open} onToggle={ev.toggle} />
      {ev.open && <EvidenceQuotes item={item} />}
    </li>
  )
}
