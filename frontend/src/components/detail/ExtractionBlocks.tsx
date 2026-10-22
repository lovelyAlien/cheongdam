import { useState } from 'react'
import type { Extraction, ExtractedItem } from '../../api/types'
import { CONSTRAINT_LABEL, SUPPLY_TYPE_LABEL } from '../../lib/labels'

/** 근거 칩. 쪽 번호만 보인다. 원문 뷰어(S-06)가 생기기 전까지 문구는 마우스를 올리면 보인다. */
function EvidenceChip({ item }: { item: ExtractedItem }) {
  const first = item.evidence[0]
  if (!first) return null
  const more = item.evidence.length - 1
  return (
    <span className="evidence" title={first.quote}>
      p.{first.page}
      {more > 0 ? ` 외 ${more}` : ''}
    </span>
  )
}

const PENDING = '공고문을 읽고 정리하고 있어요. 보통 몇 시간 안에 준비돼요.'
const FAILED = '이 공고는 자동 정리를 제공하지 못했어요. 공고문을 직접 확인해 주세요.'

interface Props {
  extraction: Extraction
  noticeUrl: string
  noticeType: string
}

export function ExtractionBlocks({ extraction, noticeUrl, noticeType }: Props) {
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
      <Constraints extraction={extraction} />
      <Conditions extraction={extraction} noticeType={noticeType} />
    </>
  )
}

function Constraints({ extraction }: { extraction: Extraction }) {
  const { items, missing_codes: missing } = extraction.constraints
  return (
    <section id="constraints" className="block">
      <h2>당첨되면 이렇게 돼요</h2>
      {items.length === 0 ? (
        <p className="muted">이 공고문에서 당첨 후 제약을 찾지 못했어요. 공고문의 유의사항을 직접 확인해 주세요.</p>
      ) : (
        <ul className="rows">
          {items.map((i) => (
            <li key={i.id} className={i.status === 'needs_review' ? 'row review' : 'row'}>
              <div className="row-head">
                <strong>{CONSTRAINT_LABEL[i.code ?? ''] ?? i.code}</strong>
                <EvidenceChip item={i} />
              </div>
              <p>{i.text}</p>
              {i.status === 'needs_review' && <span className="review-label">원문 확인 필요</span>}
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

function Conditions({ extraction, noticeType }: { extraction: Extraction; noticeType: string }) {
  const { supply_types: types, items } = extraction.conditions
  const [selected, setSelected] = useState(types[0]?.code ?? '')
  // 체크 상태는 브라우저 메모리에만 둔다. 서버, localStorage, sessionStorage에 쓰지 않는다(P-09, D-18).
  const [checked, setChecked] = useState<Record<string, number[]>>({})

  const forType = items.filter((i) => i.targets.includes('전체') || i.targets.includes(selected))
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
            <li key={i.id} className={i.status === 'needs_review' ? 'row review' : 'row'}>
              <label className="check">
                {i.status === 'confirmed' ? (
                  <input type="checkbox" checked={done.includes(i.id)} onChange={() => toggle(i.id)} />
                ) : (
                  <span className="check-spacer" />
                )}
                <span>{i.text}</span>
              </label>
              <div className="row-head">
                {i.status === 'needs_review' ? <span className="review-label">원문 확인 필요</span> : <span />}
                <EvidenceChip item={i} />
              </div>
            </li>
          ))}
        </ul>
      )}
      {checkable.length > 0 && <p className="muted small">{checkable.length}개 중 {done.length}개 체크</p>}

      {rules.length > 0 && (
        <details className="rules">
          <summary>일반 규정 ({rules.length})</summary>
          <ul className="rows">
            {rules.map((i) => (
              <li key={i.id} className="row">
                <p>{i.text}</p>
                <EvidenceChip item={i} />
              </li>
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
