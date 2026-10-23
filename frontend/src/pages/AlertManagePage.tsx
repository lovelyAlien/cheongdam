import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { ApiError, fetchManage, unsubscribeAll, unsubscribeOne, type ManagedSubscription } from '../api/alerts'
import { AlertPage } from '../components/AlertPage'
import { conditionsToSearch, summarize } from '../lib/alertConditions'
import { writeEdit } from '../lib/alertEdit'

type State =
  | { kind: 'loading' }
  | { kind: 'ok'; email: string; subs: ManagedSubscription[] }
  | { kind: 'gone' }
  | { kind: 'done' }
  | { kind: 'error' }

/** S-08c. 메일 하단의 `알림 설정`. 이 이메일의 구독 조건(최대 3개)을 보고 바꾸고 해지한다. */
export function AlertManagePage() {
  const token = useSearchParams()[0].get('token') ?? ''
  const navigate = useNavigate()
  const [state, setState] = useState<State>({ kind: 'loading' })
  const [busy, setBusy] = useState(false)

  // 작업 뒤에는 토큰이 가리키던 구독이 사라질 수 있다. 그때의 404는 오류가 아니라 모두 해지된 것이다
  const load = useCallback(
    (afterAction: boolean) =>
      fetchManage(token)
        .then((r) => setState({ kind: 'ok', email: r.email_masked, subs: r.subscriptions }))
        .catch((e: unknown) =>
          setState(
            e instanceof ApiError && e.status === 404 ? { kind: afterAction ? 'done' : 'gone' } : { kind: 'error' },
          ),
        ),
    [token],
  )

  useEffect(() => {
    load(false)
  }, [load])

  const act = async (run: () => Promise<void>, message: string) => {
    if (!window.confirm(message)) return
    setBusy(true)
    try {
      await run()
      await load(true)
    } catch {
      setState({ kind: 'error' })
    } finally {
      setBusy(false)
    }
  }

  const edit = (s: ManagedSubscription) => {
    writeEdit({ id: s.id, token })
    navigate(`/?${conditionsToSearch(s.conditions)}`)
  }

  return (
    <AlertPage>
      {state.kind === 'loading' && <p className="state">불러오는 중이에요.</p>}
      {state.kind === 'ok' && (
        <>
          <h2 className="page-title">알림 설정</h2>
          <p className="muted">{state.email}로 알림을 받고 있어요.</p>
          <ul className="alert-list">
            {state.subs.map((s) => (
              <li key={s.id} className="block">
                <p className="alert-summary">{summarize(s.conditions)}</p>
                {s.status === 'pending' && <p className="hint">확인 메일의 링크를 누르면 알림이 시작돼요.</p>}
                <div className="alert-actions">
                  <Link className="btn small-btn" to={`/?${conditionsToSearch(s.conditions)}`}>
                    목록에서 보기
                  </Link>
                  <button type="button" className="btn small-btn" disabled={busy} onClick={() => edit(s)}>
                    조건 바꾸기
                  </button>
                  <button
                    type="button"
                    className="btn small-btn"
                    disabled={busy}
                    onClick={() => act(() => unsubscribeOne(token, s.id), '이 조건의 알림을 해지할까요?')}
                  >
                    이 조건 해지
                  </button>
                </div>
              </li>
            ))}
          </ul>
          <p>
            <button
              type="button"
              className="btn ghost"
              disabled={busy}
              onClick={() => act(() => unsubscribeAll(token), '모든 알림을 그만 받을까요?')}
            >
              모든 알림 그만 받기
            </button>
          </p>
        </>
      )}
      {state.kind === 'done' && (
        <div className="state">
          <h2>알림을 모두 해지했어요.</h2>
          <p>이메일 주소는 바로 지웠어요.</p>
          <Link className="btn" to="/">
            공고 보러 가기
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
