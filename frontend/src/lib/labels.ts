// 블록 B 항목 이름(DATA.md 2장의 제약 항목 코드)과 블록 C 공급 유형 이름
export const CONSTRAINT_LABEL: Record<string, string> = {
  rewin_limit: '재당첨 제한',
  apply_restriction: '1순위·가점제 제한',
  ineligible_limit: '부적격 당첨 시 제한',
  resale_limit: '전매제한',
  residence_duty: '실거주 의무',
  payment: '계약금·중도금·잔금',
  duplicate_apply: '중복 청약 제한',
}

export const SUPPLY_TYPE_LABEL: Record<string, string> = {
  general_1st: '일반 1순위',
  general_2nd: '일반 2순위',
  newlywed: '신혼부부',
  first_home: '생애최초',
  multi_child: '다자녀',
  newborn: '신생아',
  elderly_parent: '노부모부양',
  youth: '청년',
  institution: '기관추천',
  etc: '기타',
  no_rank: '무순위',
  resupply: '재공급',
}
