// API.md 5장. 구독 관리는 메일 속 토큰이 권한이다.
import type { Conditions } from '../lib/alertConditions'

export class ApiError extends Error {
  status: number
  code: string
  field?: string

  constructor(status: number, code: string, message: string, field?: string) {
    super(message)
    this.status = status
    this.code = code
    this.field = field
  }
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, init)
  const text = await res.text()
  if (!res.ok) {
    const e = (text ? JSON.parse(text) : {}).error
    throw new ApiError(res.status, e?.code ?? 'internal', e?.message ?? '', e?.field)
  }
  return (text ? JSON.parse(text) : undefined) as T
}

const json = (method: string, body?: unknown): RequestInit => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: body === undefined ? undefined : JSON.stringify(body),
})
const q = (token: string) => `token=${encodeURIComponent(token)}`

export interface ManagedSubscription {
  id: number
  conditions: Conditions
  status: 'pending' | 'active'
}

export const subscribe = (email: string, conditions: Conditions) =>
  call<{ status: string }>('/api/alerts/subscriptions', json('POST', { email, consent: true, conditions }))

export const confirm = (token: string) =>
  call<{ conditions: Conditions; match_count: number }>('/api/alerts/confirm', json('POST', { token }))

export const fetchManage = (token: string) =>
  call<{ email_masked: string; subscriptions: ManagedSubscription[] }>(`/api/alerts/manage?${q(token)}`)

export const changeConditions = (token: string, id: number, conditions: Conditions) =>
  call<{ conditions: Conditions }>(`/api/alerts/manage/subscriptions/${id}?${q(token)}`, json('PUT', { conditions }))

export const unsubscribeOne = (token: string, id: number) =>
  call<void>(`/api/alerts/manage/subscriptions/${id}?${q(token)}`, { method: 'DELETE' })

export const unsubscribeAll = (token: string) =>
  call<void>(`/api/alerts/manage/unsubscribe-all?${q(token)}`, { method: 'POST' })

export const unsubscribeByLink = (token: string) =>
  call<{ conditions: Conditions }>('/api/alerts/unsubscribe', json('POST', { token }))

export const sendManageLink = (email: string) =>
  call<void>('/api/alerts/manage-link', json('POST', { email }))
