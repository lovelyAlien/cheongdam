package app.cheongdam.util;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** API의 offset·limit을 그대로 쓰는 Pageable. PageRequest는 offset이 size의 배수여야 해서 쓰지 못한다. */
public final class OffsetLimitRequest implements Pageable {

	private final long offset;
	private final int limit;

	public OffsetLimitRequest(long offset, int limit) {
		if (limit < 1) {
			throw new IllegalArgumentException("limit은 1 이상이어야 해요.");
		}
		this.offset = offset;
		this.limit = limit;
	}

	@Override
	public int getPageNumber() {
		return (int) (offset / limit);
	}

	@Override
	public int getPageSize() {
		return limit;
	}

	@Override
	public long getOffset() {
		return offset;
	}

	@Override
	public Sort getSort() {
		return Sort.unsorted();
	}

	@Override
	public Pageable next() {
		return new OffsetLimitRequest(offset + limit, limit);
	}

	@Override
	public Pageable previousOrFirst() {
		return hasPrevious() ? new OffsetLimitRequest(Math.max(0, offset - limit), limit) : first();
	}

	@Override
	public Pageable first() {
		return new OffsetLimitRequest(0, limit);
	}

	@Override
	public Pageable withPage(int pageNumber) {
		return new OffsetLimitRequest((long) pageNumber * limit, limit);
	}

	@Override
	public boolean hasPrevious() {
		return offset > 0;
	}
}
