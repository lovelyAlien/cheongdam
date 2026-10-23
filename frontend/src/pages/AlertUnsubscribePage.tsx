import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiError, unsubscribeByLink } from '../api/alerts'
import { AlertPage } from '../components/AlertPage'
import { conditionsToSearch, summarize, type Conditions } from '../lib/alertConditions'

type State = { kind: 'loading' } | { kind: 'ok'; conditions: Conditions } | { kind: 'gone' } | { kind: 'error' }

/** S-08d. 메일의 `알림 그만 받기`. 확인 단계 없이 그 메일이 속한 조건 하나만 바로 해지한다. */
export function AlertUnsubscribePage() {
  const token = useSearchParams()[0].get('token') ?? ''
  const [state, setState] = useState<State>({ kind: 'loading' })
  const started = useRef(false) // StrictMode가 효과를 두 번 돌려도 해지는 한 번만 부른다

  useEffect(() => {
    if (started.current) return
    started.current = true
    unsubscribeByLink(token)
      .then((r) => setState({ kind: 'ok', conditions: r.conditions }))
      .catch((e: unknown) => setState(e instanceof ApiError && e.status === 404 ? { kind: 'gone' } : { kind: 'error' }))
  }, [token])

  // 이메일은 이미 지워졌을 수 있어 새로 입력받는다. 구독 시트가 이 조건으로 채워져 열린다(D-24)
  const resubscribe = (c: Conditions) => {
    const p = new URLSearchParams(conditionsToSearch(c))
    p.set('subscribe', '1')
    return `/?${p.toString()}`
  }

  return (
    <AlertPage>
      {state.kind === 'loading' && <p className="state">해지하는 중이에요.</p>}
      {state.kind === 'ok' && (
        <div className="state">
          <h2>알림을 그만 보낼게요.</h2>
          <p className="alert-summary">{summarize(state.conditions)}</p>
          <Link className="btn" to={resubscribe(state.conditions)}>
            다시 구독하기
          </Link>
        </div>
      )}
      {state.kind === 'gone' && (
        <div className="state">
          <p>이미 해지되었거나 찾을 수 없는 알림이에요.</p>
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
