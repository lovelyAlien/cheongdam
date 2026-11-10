import { API_BASE } from './base'
import type { NoticeCalendarData, NoticeDetail, NoticeList, NoticeMapData } from './types'

export async function fetchNotices(query: URLSearchParams, signal?: AbortSignal): Promise<NoticeList> {
  const res = await fetch(`${API_BASE}/api/notices?${query.toString()}`, { signal })
  if (!res.ok) {
    throw new Error(`notices ${res.status}`)
  }
  return res.json()
}

export class NotFoundError extends Error {}

export async function fetchNotice(no: string, signal?: AbortSignal): Promise<NoticeDetail> {
  const res = await fetch(`${API_BASE}/api/notices/${encodeURIComponent(no)}`, { signal })
  if (res.status === 404) {
    throw new NotFoundError()
  }
  if (!res.ok) {
    throw new Error(`notice ${res.status}`)
  }
  return res.json()
}

export async function fetchNoticeMap(query: URLSearchParams, signal?: AbortSignal): Promise<NoticeMapData> {
  const res = await fetch(`${API_BASE}/api/notices/map?${query.toString()}`, { signal })
  if (!res.ok) {
    throw new Error(`notices/map ${res.status}`)
  }
  return res.json()
}

export async function fetchNoticeCalendar(query: URLSearchParams, signal?: AbortSignal): Promise<NoticeCalendarData> {
  const res = await fetch(`${API_BASE}/api/notices/calendar?${query.toString()}`, { signal })
  if (!res.ok) {
    throw new Error(`notices/calendar ${res.status}`)
  }
  return res.json()
}
