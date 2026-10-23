package app.cheongdam.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 메일 제목에 쓰는 구독 조건 한 줄 요약. 화면(`lib/alertConditions.ts`)과 같은 표기를 쓴다. */
public final class ConditionsSummary {

	private static final Map<String, String> LABELS = Map.ofEntries(
			Map.entry("newlywed", "신혼부부"), Map.entry("first_home", "생애최초"), Map.entry("multi_child", "다자녀"),
			Map.entry("newborn", "신생아"), Map.entry("elderly_parent", "노부모부양"), Map.entry("youth", "청년"),
			Map.entry("institution", "기관추천"), Map.entry("le60", "60㎡ 이하"), Map.entry("60to85", "60~85㎡"),
			Map.entry("gt85", "85㎡ 초과"), Map.entry("general", "일반 분양"), Map.entry("no_rank", "무순위"),
			Map.entry("resupply", "불법행위 재공급"), Map.entry("private", "민영"), Map.entry("public", "국민"));

	private ConditionsSummary() {
	}

	/** `서울·경기 신혼부부 5억 이하`. 조건이 없으면 `모든 공고`. */
	public static String of(Map<String, Object> c) {
		List<String> parts = new ArrayList<>();
		List<String> region = list(c.get("region"));
		if (!region.isEmpty()) {
			parts.add(region.size() >= 3 ? region.get(0) + " 외 " + (region.size() - 1) : String.join("·", region));
		}
		for (String key : List.of("supply", "area", "notice_type", "house_kind")) {
			List<String> v = list(c.get(key)).stream().map(x -> LABELS.getOrDefault(x, x)).toList();
			if (!v.isEmpty()) {
				parts.add(String.join("·", v));
			}
		}
		Object min = c.get("price_min");
		Object max = c.get("price_max");
		if (min != null || max != null) {
			String lo = min == null ? null : eok(min);
			String hi = max == null ? null : eok(max);
			parts.add(lo != null && hi != null ? lo + "~" + hi : lo != null ? lo + " 이상" : hi + " 이하");
		}
		return parts.isEmpty() ? "모든 공고" : String.join(" ", parts);
	}

	/** 제목에서는 20자까지만 쓴다. */
	public static String shorten(String s) {
		return s.length() <= 20 ? s : s.substring(0, 20) + "…";
	}

	private static List<String> list(Object v) {
		return v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
	}

	private static String eok(Object manwon) {
		double eok = ((Number) manwon).doubleValue() / 10000;
		return (eok == Math.floor(eok) ? String.valueOf((long) eok) : String.valueOf(eok)) + "억";
	}
}
