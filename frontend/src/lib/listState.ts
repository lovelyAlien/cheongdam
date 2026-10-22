/** 상세에서 뒤로 올 때 목록의 불러온 개수와 스크롤 위치를 복원하기 위해 세션 동안만 보관한다(S-01). */
export type ListState = { count: number; y: number }

const prefix = 'list-state:'

export function saveListState(key: string, state: ListState) {
  try {
    sessionStorage.setItem(prefix + key, JSON.stringify(state))
  } catch {
    // 저장소를 쓸 수 없으면 복원만 포기한다
  }
}

export function loadListState(key: string): ListState | null {
  try {
    const raw = sessionStorage.getItem(prefix + key)
    if (!raw) return null
    const s = JSON.parse(raw) as ListState
    return Number.isInteger(s.count) && s.count > 0 && Number.isFinite(s.y) ? s : null
  } catch {
    return null
  }
}
