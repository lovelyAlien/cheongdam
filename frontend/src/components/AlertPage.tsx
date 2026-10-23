import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

/** 알림 화면(S-08b~d)의 공통 틀. */
export function AlertPage({ children }: { children: ReactNode }) {
  return (
    <main className="list-page">
      <header className="topbar">
        <h1>
          <Link to="/" className="plain">
            청담
          </Link>
        </h1>
      </header>
      {children}
    </main>
  )
}
