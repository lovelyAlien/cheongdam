import { useState } from 'react'
import { ApiError, sendManageLink, subscribe } from '../api/alerts'
import { summarize, type Conditions } from '../lib/alertConditions'

interface Props {
  conditions: Conditions
  /** 특별공급·주택 구분 조건이 있으면 무순위·재공급은 알림에서 빠진다(P-04) */
  noInfoExcluded: boolean
  onClose: () => void
}

const ERRORS: Record<string, string> = {
  already_subscribed: '이미 이 조건으로 알림을 받고 있어요.',
  rate_limited: '잠시 뒤에 다시 시도해 주세요.',
}

/** S-08a. 지금 보고 있는 필터 조건을 이메일 알림으로 저장한다. 확인 메일의 링크를 눌러야 시작된다. */
export function SubscribeSheet({ conditions, noInfoExcluded, onClose }: Props) {
  const [email, setEmail] = useState('')
  const [consent, setConsent] = useState(false)
  const [consentOpen, setConsentOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [sentTo, setSentTo] = useState<string | null>(null)
  const [error, setError] = useState<{ code: string; message: string } | null>(null)
  const [linkSent, setLinkSent] = useState(false)

  const submit = async () => {
    setBusy(true)
    setError(null)
    try {
      await subscribe(email.trim(), conditions)
      setSentTo(email.trim())
    } catch (e) {
      const err = e instanceof ApiError ? e : null
      setError({
        code: err?.code ?? 'internal',
        message: ERRORS[err?.code ?? ''] ?? err?.message ?? '잠시 문제가 생겼어요. 다시 시도해 주세요.',
      })
    } finally {
      setBusy(false)
    }
  }

  const mailManageLink = async () => {
    try {
      await sendManageLink(email.trim())
      setLinkSent(true)
    } catch (e) {
      setError({ code: 'internal', message: e instanceof ApiError && e.message ? e.message : '잠시 뒤에 다시 시도해 주세요.' })
    }
  }

  return (
    <div className="sheet-backdrop" role="dialog" aria-modal="true" aria-label="알림 받기">
      <div className="sheet compact">
        <header className="sheet-head">
          <h2>이 조건으로 새 공고를 알려 드려요</h2>
          <button type="button" className="icon-btn" aria-label="닫기" onClick={onClose}>
            ✕
          </button>
        </header>

        <div className="sheet-body">
          {sentTo ? (
            <div className="state">
              <p>
                <strong>{sentTo}</strong>로 확인 메일을 보냈어요.
              </p>
              <p>메일의 링크를 눌러야 알림이 시작돼요.</p>
              <button type="button" className="btn primary" onClick={onClose}>
                닫기
              </button>
            </div>
          ) : (
            <>
              <p className="alert-summary">{summarize(conditions)}</p>
              <p className="hint">접수 예정·접수 중 공고만 알려요.</p>
              {noInfoExcluded && (
                <p className="hint">무순위·재공급 공고는 특별공급 정보가 없어 알림에서 빠져요.</p>
              )}

              <label className="field">
                이메일
                <input
                  type="email"
                  inputMode="email"
                  autoComplete="email"
                  placeholder="name@example.com"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                />
              </label>

              <div className="consent">
                <label>
                  <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
                  (필수) 개인정보 수집·이용 동의
                </label>
                <button
                  type="button"
                  className="link"
                  aria-expanded={consentOpen}
                  onClick={() => setConsentOpen(!consentOpen)}
                >
                  {consentOpen ? '접기' : '보기'}
                </button>
              </div>
              {consentOpen && (
                <p className="hint">
                  수집 항목: 이메일 주소 · 이용 목적: 조건에 맞는 새 공고와 정정 알림 발송 · 보관 기간: 해지하면 바로,
                  확인하지 않으면 7일 뒤 삭제해요.
                </p>
              )}
              <p className="hint">매일 아침, 조건에 맞는 새 공고와 정정이 있을 때만 메일을 보내요.</p>

              {error && (
                <p className="form-error" role="alert">
                  {error.message}
                </p>
              )}
              {error?.code === 'subscription_limit' && (
                <p>
                  {linkSent ? (
                    '알림 설정 메일을 보냈어요.'
                  ) : (
                    <button type="button" className="btn" onClick={mailManageLink}>
                      알림 설정 메일 받기
                    </button>
                  )}
                </p>
              )}

              <button
                type="button"
                className="btn primary wide"
                disabled={busy || !consent || email.trim() === ''}
                onClick={submit}
              >
                {busy ? '보내는 중이에요' : '확인 메일 받기'}
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
