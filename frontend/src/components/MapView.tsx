import type { NoticeMapData } from '../api/types'
import { NoticeCard } from './NoticeCard'
import { NoticeMap } from './NoticeMap'

export function MapView({
  data,
  error,
  loading,
  selected,
  highlight,
  detailQuery,
  onSelect,
  onList,
  onRetry,
}: {
  data: NoticeMapData | null
  error: boolean
  loading: boolean
  selected: string[]
  highlight: string[]
  detailQuery: string
  onSelect: (nos: string[]) => void
  onList: () => void
  onRetry: () => void
}) {
  if (error) {
    return (
      <div className="state">
        <p>지도 정보를 불러오지 못했어요.</p>
        <button type="button" className="btn" onClick={onRetry}>다시 시도</button>
      </div>
    )
  }
  if (loading || !data) return <p className="state">불러오는 중이에요.</p>

  const cards = data.items.filter((i) => selected.includes(i.house_manage_no))
  return (
    <div className="map-view">
      {data.unlocated > 0 && (
        <p className="banner">위치를 확인할 수 없는 공고 {data.unlocated}건은 목록에서 볼 수 있어요.</p>
      )}
      <div className="map-box">
        <NoticeMap pins={data.pins} items={data.items} selected={selected} onSelect={onSelect} onList={onList} />
        {data.pins.length === 0 && <p className="map-empty">지도에 표시할 공고가 없어요.</p>}
        {cards.length > 0 && (
          <div className="map-sheet">
            <div className="map-sheet-head">
              <span>{cards.length > 1 ? `이 위치의 공고 ${cards.length}건` : '선택한 공고'}</span>
              <button type="button" className="icon-btn" aria-label="닫기" onClick={() => onSelect([])}>✕</button>
            </div>
            <ul className="cards">
              {cards.map((n) => (
                <NoticeCard key={n.house_manage_no} notice={n} highlight={highlight} detailQuery={detailQuery} />
              ))}
            </ul>
          </div>
        )}
      </div>
    </div>
  )
}
