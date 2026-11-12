import { Link, NavLink } from 'react-router-dom'

/**
 * 목록(S-01)과 캘린더(S-10) 위에 같이 쓰는 상단 바. 탭을 오가도 목록 필터 쿼리를 그대로 넘긴다.
 * brandIsTitle: 화면에 다른 h1이 없으면 로고를 h1로 쓴다.
 */
export function TopNav({ query, brandIsTitle = false }: { query: string; brandIsTitle?: boolean }) {
  const qs = query ? `?${query}` : ''
  const brand = <Link className="plain" to={`/${qs}`}>청담</Link>
  return (
    <header className="topbar">
      {brandIsTitle ? <h1 className="brand">{brand}</h1> : <strong className="brand">{brand}</strong>}
      <nav className="top-tabs" aria-label="화면 전환">
        <NavLink to={`/${qs}`} end>분양 목록</NavLink>
        <NavLink to={`/calendar${qs}`}>캘린더</NavLink>
      </nav>
    </header>
  )
}
