package app.cheongdam.dto;

import app.cheongdam.exception.ApiException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** GET /api/notices의 쿼리. 이름과 값은 화면 URL·구독 conditions(v1)와 같다. */
public record NoticeFilter(
		String q,
		List<String> status,
		List<String> region,
		List<String> supply,
		Integer priceMin,
		Integer priceMax,
		List<String> area,
		List<String> noticeType,
		List<String> houseKind,
		String sort,
		int limit,
		int offset) {

	static final Set<String> STATUS = Set.of("upcoming", "open", "closed");
	static final Set<String> SUPPLY = Set.of("newlywed", "first_home", "multi_child", "newborn",
			"elderly_parent", "youth", "institution");
	static final Set<String> AREA = Set.of("le60", "60to85", "gt85");
	static final Set<String> NOTICE_TYPE = Set.of("general", "no_rank", "resupply");
	static final Set<String> HOUSE_KIND = Set.of("private", "public");
	static final Set<String> SORT = Set.of("deadline", "latest");
	static final int DEFAULT_LIMIT = 20;
	static final int MAX_LIMIT = 100;

	public static NoticeFilter parse(String q, String status, String region, String supply, String priceMin,
			String priceMax, String area, String noticeType, String houseKind, String sort, String limit,
			String offset) {
		Integer pMin = intParam("price_min", priceMin, 0, null);
		Integer pMax = intParam("price_max", priceMax, 0, null);
		if (pMin != null && pMax != null && pMin > pMax) {
			throw ApiException.invalidParam("price_min", "price_min이 price_max보다 커요.");
		}
		List<String> statuses = enumList("status", status, STATUS);
		String sortValue = sort == null || sort.isBlank() ? "deadline" : sort;
		if (!SORT.contains(sortValue)) {
			throw ApiException.invalidParam("sort", "sort는 deadline, latest 중 하나여야 해요.");
		}
		return new NoticeFilter(
				q == null || q.isBlank() ? null : q.strip(),
				statuses.isEmpty() ? List.of("upcoming", "open") : statuses,
				list(region),
				enumList("supply", supply, SUPPLY),
				pMin,
				pMax,
				enumList("area", area, AREA),
				enumList("notice_type", noticeType, NOTICE_TYPE),
				enumList("house_kind", houseKind, HOUSE_KIND),
				sortValue,
				intParam("limit", limit, 0, MAX_LIMIT) == null ? DEFAULT_LIMIT : intParam("limit", limit, 0, MAX_LIMIT),
				intParam("offset", offset, 0, null) == null ? 0 : intParam("offset", offset, 0, null));
	}

	public boolean hasSupplyOrHouseKind() {
		return !supply.isEmpty() || !houseKind.isEmpty();
	}

	private static List<String> list(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (String v : Arrays.asList(value.split(","))) {
			if (!v.isBlank() && !out.contains(v.strip())) {
				out.add(v.strip());
			}
		}
		return out;
	}

	private static List<String> enumList(String field, String value, Set<String> allowed) {
		List<String> values = list(value);
		for (String v : values) {
			if (!allowed.contains(v)) {
				throw ApiException.invalidParam(field, field + "에 쓸 수 없는 값이에요: " + v);
			}
		}
		return values;
	}

	private static Integer intParam(String field, String value, int min, Integer max) {
		if (value == null || value.isBlank()) {
			return null;
		}
		int n;
		try {
			n = Integer.parseInt(value.strip());
		} catch (NumberFormatException e) {
			throw ApiException.invalidParam(field, field + "는 정수여야 해요.");
		}
		if (n < min || (max != null && n > max)) {
			throw ApiException.invalidParam(field, field + " 범위를 벗어났어요.");
		}
		return n;
	}
}
