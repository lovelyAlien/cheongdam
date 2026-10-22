import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigationType, useSearchParams } from 'react-router-dom'
import { fetchNotices } from '../api/notices'
import type { NoticeItem, NoticeList } from '../api/types'
import { FilterSheet } from '../components/FilterSheet'
import { NoticeCard } from '../components/NoticeCard'
import {
  activeFilterCount,
  AREA_OPTIONS,
  HOUSE_KIND_OPTIONS,
  labelOf,
  NOTICE_TYPE_OPTIONS,
  PAGE_SIZE,
  parseFilters,
  SORT_OPTIONS,
  STATUS_OPTIONS,
  SUPPLY_OPTIONS,
  toParams,
  type Filters,
} from '../lib/filters'
import { formatDate } from '../lib/format'
import { useDesktop } from '../lib/useDesktop'
import { loadListState, saveListState } from '../lib/listState'

// API의 limit 상한(API.md 2장). 더 보기를 5번 넘게 눌렀어도 복원은 여기까지만 한다.
const MAX_RESTORE = 100

type Chip = { key: string; label: string; remove: (f: Filters) => Filters }

function chipsOf(f: Filters): Chip[] {
  const out: Chip[] = []
  const multi = (
    field: 'status' | 'supply' | 'area' | 'notice_type' | 'house_kind',
    options: { value: string; label: string }[],
  ) => {
    for (const v of f[field]) {
      out.push({
        key: `${field}:${v}`,
        label: labelOf(options, v),
        remove: (cur) => ({ ...cur, [field]: cur[field].filter((x) => x !== v) }),
      })
    }
  }
  if (f.status.join() !== 'upcoming,open') multi('status', STATUS_OPTIONS)
  if (f.region.length) {
    const label = f.region.length >= 3 ? `${f.region[0]} 외 ${f.region.length - 1}` : f.region.join('·')
    out.push({ key: 'region', label, remove: (cur) => ({ ...cur, region: [] }) })
  }
  multi('supply', SUPPLY_OPTIONS)
  if (f.price_min != null || f.price_max != null) {
    const lo = f.price_min != null ? `${f.price_min / 10000}억` : null
    const hi = f.price_max != null ? `${f.price_max / 10000}억` : null
    const label = lo && hi ? `${lo}~${hi}` : lo ? `${lo} 이상` : `${hi} 이하`
    out.push({
      key: 'price',
      label,
      remove: (cur) => ({ ...cur, price_min: null, price_max: null }),
    })
  }
  multi('area', AREA_OPTIONS)
  multi('notice_type', NOTICE_TYPE_OPTIONS)
  multi('house_kind', HOUSE_KIND_OPTIONS)
  return out
}

const RELAX_LABEL: Record<string, string> = {
  status: '마감 공고도 보기',
  region: '지역 조건 빼기',
  supply: '특별공급 조건 빼기',
  price: '분양가 조건 빼기',
  area: '전용면적 조건 빼기',
  notice_type: '공고 유형 조건 빼기',
  house_kind: '주택 구분 조건 빼기',
}

function relax(f: Filters, drop: string): Filters {
  switch (drop) {
    case 'status': return { ...f, status: ['upcoming', 'open', 'closed'] }
    case 'region': return { ...f, region: [] }
    case 'supply': return { ...f, supply: [] }
    case 'price': return { ...f, price_min: null, price_max: null }
    case 'area': return { ...f, area: [] }
    case 'notice_type': return { ...f, notice_type: [] }
    case 'house_kind': return { ...f, house_kind: [] }
    default: return f
  }
}

export function NoticeListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = parseFilters(searchParams)
  const key = toParams(filters).toString()

  // 불러온 결과는 어떤 요청(key)의 것인지 함께 둔다. 요청이 바뀌면 그 결과는 낡은 것이라 loading으로 본다.
  const [result, setResult] = useState<{ req: string; data: NoticeList | null; items: NoticeItem[]; error: boolean } | null>(null)
  const [moreError, setMoreError] = useState(false)
  const [sheetOpen, setSheetOpen] = useState(false)
  const desktop = useDesktop()
  const [searchText, setSearchText] = useState(filters.q)
  // 뒤로 가기 등으로 URL의 검색어가 바뀌면 입력창도 맞춘다(렌더 중 상태 조정)
  const [prevQ, setPrevQ] = useState(filters.q)
  if (prevQ !== filters.q) {
    setPrevQ(filters.q)
    setSearchText(filters.q)
  }
  const [retry, setRetry] = useState(0)
  const loadingMore = useRef(false)

  const update = useCallback(
    (next: Filters) => setSearchParams(toParams(next), { replace: false }),
    [setSearchParams],
  )

  const req = `${key}#${retry}`

  // 뒤로 가기로 들어왔을 때만 복원한다. 로고나 새 링크로 들어오면 처음부터 보여 준다.
  const navType = useNavigationType()
  const [saved] = useState(() => (navType === 'POP' ? loadListState(key) : null))
  const restored = useRef(false)
  const initialKey = useRef(key)
  const scrollY = useRef(0)
  const loadedCount = useRef(0)
  const keyRef = useRef(key)

  // 필터가 바뀌면 처음부터 다시 불러온다
  useEffect(() => {
    const ctl = new AbortController()
    const params = new URLSearchParams(key)
    // 복원은 처음 들어온 URL의 첫 불러오기에만 적용한다
    const count = saved && !restored.current && key === initialKey.current ? Math.min(saved.count, MAX_RESTORE) : PAGE_SIZE
    params.set('limit', String(count))
    fetchNotices(params, ctl.signal)
      .then((r) => setResult({ req, data: r, items: r.items, error: false }))
      .catch((e: unknown) => {
        if ((e as Error).name === 'AbortError') return
        setResult({ req, data: null, items: [], error: true })
      })
    return () => ctl.abort()
  }, [key, retry, req, saved])

  // 검색어는 300ms 디바운스 후 URL에 반영
  useEffect(() => {
    if (searchText === filters.q) return
    const t = setTimeout(() => update({ ...filters, q: searchText.trim() }), 300)
    return () => clearTimeout(t)
  }, [searchText, filters, update])

  const current = result && result.req === req ? result : null
  const loading = current === null
  const data = current?.data ?? null
  const items = current?.items ?? []
  const error = (current?.error ?? false) || moreError

  useEffect(() => {
    loadedCount.current = items.length
    keyRef.current = key
  })

  // 스크롤 위치는 계속 기억해 두었다가 목록을 떠날 때 저장한다
  useEffect(() => {
    const onScroll = () => {
      scrollY.current = window.scrollY
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      window.removeEventListener('scroll', onScroll)
      if (loadedCount.current > 0) {
        saveListState(keyRef.current, { count: loadedCount.current, y: scrollY.current })
      }
    }
  }, [])

  // 복원할 개수만큼 그려진 뒤에 한 번만 스크롤한다
  useEffect(() => {
    if (!current || !saved || restored.current) return
    restored.current = true
    if (key === initialKey.current && !current.error) window.scrollTo(0, saved.y)
  }, [current, key, saved])

  const loadMore = () => {
    if (loadingMore.current || !data) return
    loadingMore.current = true
    const params = new URLSearchParams(key)
    params.set('limit', String(PAGE_SIZE))
    params.set('offset', String(items.length))
    fetchNotices(params)
      .then((r) => setResult((cur) => (cur && cur.req === req ? { ...cur, items: [...cur.items, ...r.items] } : cur)))
      .catch(() => setMoreError(true))
      .finally(() => {
        loadingMore.current = false
      })
  }

  const count = activeFilterCount(filters)
  const chips = chipsOf(filters)
  const stale = data?.last_crawl.stale
  const crawlAt = data?.last_crawl.finished_at

  return (
    <main className={desktop ? 'list-page desktop' : 'list-page'}>
      <header className="topbar">
        <h1>청담</h1>
      </header>

      <div className="search">
        <input
          type="search"
          placeholder="단지명 검색"
          aria-label="단지명 검색"
          value={searchText}
          onChange={(e) => setSearchText(e.target.value)}
        />
      </div>

      <div className="list-layout">
      {desktop && <FilterSheet inline initial={filters} onApply={update} />}
      <div className="list-main">
      <div className="filterbar">
        {!desktop && (
          <button type="button" className="btn filter-btn" onClick={() => setSheetOpen(true)}>
            필터{count > 0 ? ` ${count}` : ''}
          </button>
        )}
        <div className="active-chips">
          {chips.map((c) => (
            <button key={c.key} type="button" className="chip on small" onClick={() => update(c.remove(filters))}>
              {c.label} ✕
            </button>
          ))}
        </div>
      </div>

      <div className="resultbar">
        <span>
          {data ? `공고 ${data.total}건` : ' '}
          {stale && (
            <span className="stale">
              {' '}
              · {crawlAt ? `마지막 업데이트 ${formatDate(crawlAt.slice(0, 10))} ${crawlAt.slice(11, 16)}` : '업데이트 정보 없음'}
            </span>
          )}
        </span>
        <select
          aria-label="정렬"
          value={filters.sort}
          onChange={(e) => update({ ...filters, sort: e.target.value })}
        >
          {SORT_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
      </div>

      {data && data.no_info_excluded > 0 && (
        <div className="banner">
          주택 구분·특별공급 정보가 없는 무순위·재공급 공고 {data.no_info_excluded}건은 제외했어요.{' '}
          <button type="button" className="link" onClick={() => update({ ...filters, supply: [], house_kind: [] })}>
            포함해서 보기
          </button>
        </div>
      )}

      {error && (
        <div className="state">
          <p>공고를 불러오지 못했어요.</p>
          <button type="button" className="btn" onClick={() => {
                setMoreError(false)
                setRetry((n) => n + 1)
              }}>다시 시도</button>
        </div>
      )}

      {!error && loading && <p className="state">불러오는 중이에요.</p>}

      {!error && !loading && data && data.total === 0 && (
        <div className="state">
          <p>
            {filters.q
              ? `'${filters.q}' 단지를 찾지 못했어요. 필터 조건 안에서만 찾았어요.`
              : '조건에 맞는 공고가 없어요.'}
          </p>
          <div className="relax">
            {filters.q && activeFilterCount(filters) > 0 && (
              <button type="button" className="btn" onClick={() => update({ ...parseFilters(new URLSearchParams()), q: filters.q })}>
                필터 풀고 검색
              </button>
            )}
            {data.relaxations.map((r) => (
              <button key={r.drop} type="button" className="btn" onClick={() => update(relax(filters, r.drop))}>
                {RELAX_LABEL[r.drop] ?? r.drop} ({r.total}건)
              </button>
            ))}
          </div>
        </div>
      )}

      <ul className="cards">
        {items.map((n) => (
          <NoticeCard key={n.house_manage_no} notice={n} highlight={filters.supply} />
        ))}
      </ul>

      {!error && data && items.length < data.total && (
        <div className="more">
          <button type="button" className="btn" onClick={loadMore}>더 보기</button>
        </div>
      )}

      </div>
      </div>

      <footer className="footer">
        청담은 청약홈(한국부동산원)의 공개 데이터와 공고문을 바탕으로 정보를 제공하며, 정보의 정확성을 보증하지 않습니다.
      </footer>

      {sheetOpen && !desktop && (
        <FilterSheet
          initial={filters}
          onClose={() => setSheetOpen(false)}
          onApply={(f) => {
            setSheetOpen(false)
            update(f)
          }}
        />
      )}
    </main>
  )
}
