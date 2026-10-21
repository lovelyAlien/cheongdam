import type { NoticeList } from './types'

export async function fetchNotices(query: URLSearchParams, signal?: AbortSignal): Promise<NoticeList> {
  const res = await fetch(`/api/notices?${query.toString()}`, { signal })
  if (!res.ok) {
    throw new Error(`notices ${res.status}`)
  }
  return res.json()
}
