import { BrowserRouter, Link, Route, Routes } from 'react-router-dom'
import { AlertConfirmPage } from './pages/AlertConfirmPage'
import { AlertManagePage } from './pages/AlertManagePage'
import { AlertUnsubscribePage } from './pages/AlertUnsubscribePage'
import { NoticeDetailPage } from './pages/NoticeDetailPage'
import { NoticeListPage } from './pages/NoticeListPage'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<NoticeListPage />} />
        <Route path="/notices/:no" element={<NoticeDetailPage />} />
        <Route path="/alerts/confirm" element={<AlertConfirmPage />} />
        <Route path="/alerts/manage" element={<AlertManagePage />} />
        <Route path="/alerts/unsubscribe" element={<AlertUnsubscribePage />} />
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
