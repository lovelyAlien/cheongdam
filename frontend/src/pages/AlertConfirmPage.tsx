import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiError, confirm } from '../api/alerts'
import { AlertPage } from '../components/AlertPage'
import { conditionsToSearch, summarize, type Conditions } from '../lib/alertConditions'

type State =
  | { kind: 'loading' }
  | { kind: 'ok'; conditions: Conditions; count: number }
  | { kind: 'expired' }
  | { kind: 'error' }

/** S-08b. M-01의 확인 링크. 삭제된 확인 토큰은 404라 만료(410)와 같은 문구로 보여 준다(E-14, D-27). */
export function AlertConfirmPage() {
  const token = useSearchParams()[0].get('token') ?? ''
  const [state, setState] = useState<State>({ kind: 'loading' })
  const started = useRef(false)

  useEffect(() => {
    if (started.current) return
    started.current = true
    confirm(token)
      .then((r) => setState({ kind: 'ok', conditions: r.conditions, count: r.match_count }))
      .catch((e: unknown) =>
        setState(e instanceof ApiError && (e.status === 404 || e.status === 410) ? { kind: 'expired' } : { kind: 'error' }),
      )
  }, [token])

  return (
    <AlertPage>
      {state.kind === 'loading' && <p className="state">확인하는 중이에요.</p>}
      {state.kind === 'ok' && (
        <div className="state">
          <h2>알림을 시작했어요.</h2>
          <p>내일 아침부터 조건에 맞는 새 공고를 알려 드려요.</p>
          <p className="alert-summary">{summarize(state.conditions)}</p>
          <p>지금 조건에 맞는 접수 예정·접수 중 공고는 {state.count}건이에요.</p>
          <Link className="btn primary" to={`/?${conditionsToSearch(state.conditions)}`}>
            지금 공고 보기
          </Link>
        </div>
      )}
      {state.kind === 'expired' && (
        <div className="state">
          <p>확인 기간이 지났어요. 다시 구독해 주세요.</p>
          <Link className="btn primary" to="/">
            알림 받기
          </Link>
        </div>
      )}
      {state.kind === 'error' && (
        <div className="state">
          <p>잠시 문제가 생겼어요.</p>
          <button type="button" className="btn" onClick={() => window.location.reload()}>
            다시 시도
          </button>
        </div>
      )}
    </AlertPage>
  )
}
