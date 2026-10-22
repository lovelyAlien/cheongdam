package app.cheongdam.dto;

import app.cheongdam.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 구독 조건 v1(SCHEMA.md 8장). 화면 필터와 같은 키·값이고 접수 상태·검색어·정렬은 받지 않는다.
 * 값 검증은 목록 API의 {@link NoticeFilter}에 맡겨 필터 해석이 한 곳에만 있게 한다(D-28).
 */
public final class SubscriptionConditions {

	private static final Set<String> LIST_KEYS = Set.of("region", "supply", "area", "notice_type", "house_kind");
	private static final Set<String> PRICE_KEYS = Set.of("price_min", "price_max");
	private static final int MAX_REGIONS = 17;
	private static final int MAX_REGION_LENGTH = 10;

	private SubscriptionConditions() {
	}

	/** 키를 정렬하고 값 배열을 정렬·중복 제거한 조건. 같은 조건이 순서만 달라 중복 저장되지 않게 한다(E-17). */
	public static Map<String, Object> normalize(Map<String, Object> raw) {
		if (raw == null || !Integer.valueOf(1).equals(raw.get("v"))) {
			throw ApiException.invalidParam("conditions", "conditions.v는 1이어야 해요.");
		}
		Map<String, Object> out = new TreeMap<>();
		out.put("v", 1);
		for (var e : raw.entrySet()) {
			String key = e.getKey();
			if (key.equals("v")) {
				continue;
			}
			if (LIST_KEYS.contains(key)) {
				List<String> values = strings(key, e.getValue());
				if (!values.isEmpty()) {
					out.put(key, values);
				}
			} else if (PRICE_KEYS.contains(key)) {
				if (e.getValue() != null) {
					out.put(key, price(key, e.getValue()));
				}
			} else {
				throw ApiException.invalidParam(key, key + "는 알림 조건에 쓸 수 없어요.");
			}
		}
		toFilter(out); // 값 범위·허용 값은 목록 필터와 같은 규칙으로 확인
		return out;
	}

	/** 구독 조건으로 지금 맞는 공고를 세거나 찾을 때 쓰는 필터. 접수 상태는 기본값(접수 예정·접수 중). */
	@SuppressWarnings("unchecked")
	public static NoticeFilter toFilter(Map<String, Object> normalized) {
		return NoticeFilter.parse(null, null, join(normalized.get("region")), join(normalized.get("supply")),
				str(normalized.get("price_min")), str(normalized.get("price_max")), join(normalized.get("area")),
				join(normalized.get("notice_type")), join(normalized.get("house_kind")), null, null, null);
	}

	public static String hash(String canonicalJson) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(d);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static List<String> strings(String key, Object value) {
		if (value == null) {
			return List.of();
		}
		if (!(value instanceof List<?> list)) {
			throw ApiException.invalidParam(key, key + "는 목록이어야 해요.");
		}
		TreeSet<String> out = new TreeSet<>();
		for (Object o : list) {
			if (!(o instanceof String s) || s.isBlank()) {
				throw ApiException.invalidParam(key, key + "에 쓸 수 없는 값이에요.");
			}
			out.add(s.strip());
		}
		if (key.equals("region")
				&& (out.size() > MAX_REGIONS || out.stream().anyMatch(r -> r.length() > MAX_REGION_LENGTH))) {
			throw ApiException.invalidParam(key, "region에 쓸 수 없는 값이에요.");
		}
		return new ArrayList<>(out);
	}

	private static Integer price(String key, Object value) {
		if (value instanceof Integer i) {
			return i;
		}
		throw ApiException.invalidParam(key, key + "는 정수여야 해요.");
	}

	private static String join(Object value) {
		return value instanceof List<?> l && !l.isEmpty() ? String.join(",", l.stream().map(String::valueOf).toList()) : null;
	}

	private static String str(Object value) {
		return value == null ? null : value.toString();
	}
}
