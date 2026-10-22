import { BrowserRouter, Link, Route, Routes } from 'react-router-dom'
import { NoticeListPage } from './pages/NoticeListPage'

function NoticeDetailPlaceholder() {
  return (
    <main className="list-page">
      <p className="state">공고 상세는 준비 중이에요.</p>
      <p className="state">
        <Link to="/">공고 목록으로</Link>
      </p>
    </main>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<NoticeListPage />} />
        <Route path="/notices/:no" element={<NoticeDetailPlaceholder />} />
        <Route
          path="*"
          element={
            <main className="list-page">
              <p className="state">찾는 페이지가 없어요.</p>
              <p className="state">
                <Link to="/">공고 목록으로</Link>
              </p>
            </main>
          }
        />
      </Routes>
    </BrowserRouter>
  )
}
