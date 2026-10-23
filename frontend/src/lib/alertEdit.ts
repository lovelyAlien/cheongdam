// S-08c `조건 바꾸기`: 관리 화면에서 목록으로 건너가 필터를 바꾼 뒤 저장하기까지 어떤 구독을 고치는지 기억한다.
// 토큰은 이 탭이 닫히면 사라지도록 sessionStorage에만 둔다.
const KEY = 'alert-edit'

export interface EditTarget {
  id: number
  token: string
}

export function readEdit(): EditTarget | null {
  try {
    const raw = sessionStorage.getItem(KEY)
    return raw ? (JSON.parse(raw) as EditTarget) : null
  } catch {
    return null
  }
}

export function writeEdit(t: EditTarget) {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(t))
  } catch {
    // 저장소를 쓸 수 없으면 조건 바꾸기만 못 한다
  }
}

export function clearEdit() {
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    // 무시
  }
}
