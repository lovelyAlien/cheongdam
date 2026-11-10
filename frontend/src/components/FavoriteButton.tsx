import { useFavorites } from '../lib/favorites'

/** 관심 공고 ♡ 버튼. 목록 카드는 아이콘만, 상세 하단 바는 글자와 함께 쓴다. */
export function FavoriteButton({ no, name, withLabel = false }: { no: string; name: string; withLabel?: boolean }) {
  const { ids, toggle } = useFavorites()
  const on = ids.includes(no)
  return (
    <button
      type="button"
      className={withLabel ? 'btn fav-btn' : 'fav-btn icon'}
      aria-pressed={on}
      aria-label={withLabel ? undefined : `${name} 관심 공고${on ? '에서 빼기' : '로 표시'}`}
      onClick={() => toggle(no)}
    >
      <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
        <path
          d="M12 20.5l-1.3-1.2C6 15.1 3 12.4 3 8.9 3 6.1 5.2 4 7.9 4c1.6 0 3.1.7 4.1 1.9C13 4.7 14.5 4 16.1 4 18.8 4 21 6.1 21 8.9c0 3.5-3 6.2-7.7 10.4L12 20.5z"
          fill={on ? 'currentColor' : 'none'}
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinejoin="round"
        />
      </svg>
      {withLabel && <span>관심</span>}
    </button>
  )
}
