// 표기 규칙은 SERVICE_SPEC P-03을 따른다. API는 원값을 주고 표기는 화면이 한다.

/** 52340 → 5억 2,340만 원 */
export function formatPriceMan(man: number): string {
  const eok = Math.floor(man / 10000)
  const rest = man % 10000
  if (eok === 0) return `${rest.toLocaleString('ko-KR')}만 원`
  if (rest === 0) return `${eok}억 원`
  return `${eok}억 ${rest.toLocaleString('ko-KR')}만 원`
}

/** 카드의 분양가 범위: 3.2억 ~ 5.2억 */
export function formatPriceRange(min: number | null, max: number | null): string {
  if (min == null || max == null) return '공고문 확인'
  const eok = (v: number) => `${(Math.round(v / 1000) / 10).toString()}억`
  const a = eok(min)
  const b = eok(max)
  return a === b ? a : `${a} ~ ${b}`
}

/** 카드의 전용면적 범위: 59~84㎡ (소수점 버림, 같으면 하나) */
export function formatAreaRange(min: number | null, max: number | null): string | null {
  if (min == null || max == null) return null
  const a = Math.floor(min)
  const b = Math.floor(max)
  return a === b ? `${a}㎡` : `${a}~${b}㎡`
}

export function formatHouseholds(n: number | null): string | null {
  return n == null ? null : `${n.toLocaleString('ko-KR')}세대`
}

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토']

/** 올해면 연도 생략, 요일 표기: 10.21(수) / 2027.01.05(화) */
export function formatDate(iso: string, now: Date = new Date()): string {
  const [y, m, d] = iso.split('-').map(Number)
  const weekday = WEEKDAYS[new Date(y, m - 1, d).getDay()]
  const md = `${String(m).padStart(2, '0')}.${String(d).padStart(2, '0')}`
  return y === now.getFullYear() ? `${md}(${weekday})` : `${y}.${md}(${weekday})`
}

export const STAGE_LABEL: Record<string, string> = {
  special: '특별공급',
  rank1: '1순위',
  rank2: '2순위',
  no_rank: '무순위 접수',
  general: '일반공급',
  winner: '당첨자 발표',
  contract: '계약',
}

export const AREA_LABEL: Record<string, string> = {
  local: '해당지역',
  gyeonggi: '기타경기',
  other: '기타지역',
}

export function formatDateRange(start: string, end: string, now: Date = new Date()): string {
  return start === end ? formatDate(start, now) : `${formatDate(start, now)} ~ ${formatDate(end, now)}`
}

/** 202903 → 2029년 3월 */
export function formatMoveIn(ym: string | null): string | null {
  return ym && /^\d{6}$/.test(ym) ? `${ym.slice(0, 4)}년 ${Number(ym.slice(4))}월` : null
}

/** 전용면적 84.886 + 084.8860A → 84.89㎡ A */
export function formatUnit(exclusive: number, houseTy: string): string {
  const suffix = houseTy.match(/[A-Za-z가-힣]+$/)?.[0] ?? ''
  const area = (Math.round(exclusive * 100) / 100).toString()
  return suffix ? `${area}㎡ ${suffix}` : `${area}㎡`
}

export function formatDateTime(iso: string): string {
  return `${formatDate(iso.slice(0, 10))} ${iso.slice(11, 16)}`
}
