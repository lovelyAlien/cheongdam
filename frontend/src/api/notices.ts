import type { NoticeDetail, NoticeList } from './types'

export async function fetchNotices(query: URLSearchParams, signal?: AbortSignal): Promise<NoticeList> {
  const res = await fetch(`/api/notices?${query.toString()}`, { signal })
  if (!res.ok) {
    throw new Error(`notices ${res.status}`)
  }
  return res.json()
}

export class NotFoundError extends Error {}

export async function fetchNotice(no: string, signal?: AbortSignal): Promise<NoticeDetail> {
  const res = await fetch(`/api/notices/${encodeURIComponent(no)}`, { signal })
  if (res.status === 404) {
    throw new NotFoundError()
  }
  if (!res.ok) {
    throw new Error(`notice ${res.status}`)
  }
  return res.json()
}
