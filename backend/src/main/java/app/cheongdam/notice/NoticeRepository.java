package app.cheongdam.notice;

import app.cheongdam.notice.NoticeListResponse.Item;
import app.cheongdam.notice.NoticeListResponse.LastCrawl;
import app.cheongdam.notice.NoticeListResponse.Relaxation;
import app.cheongdam.notice.NoticeListResponse.SupplyTag;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class NoticeRepository {

	/** 필터를 하나씩 뺐을 때의 건수를 셀 수 있는 조건 이름(API.md 2장 relaxations). */
	static final List<String> DROPPABLE = List.of("status", "region", "supply", "price", "area", "notice_type",
			"house_kind");

	/** notice 컬럼 → API 공급 유형 코드. 기타(sp_etc)는 태그로 보이지 않는다. */
	private static final Map<String, String> SUPPLY_COLUMNS = new LinkedHashMap<>();
	static {
		SUPPLY_COLUMNS.put("newlywed", "sp_newlywed");
		SUPPLY_COLUMNS.put("first_home", "sp_first_home");
		SUPPLY_COLUMNS.put("multi_child", "sp_multi_child");
		SUPPLY_COLUMNS.put("newborn", "sp_newborn");
		SUPPLY_COLUMNS.put("elderly_parent", "sp_elderly");
		SUPPLY_COLUMNS.put("youth", "sp_youth");
		SUPPLY_COLUMNS.put("institution", "sp_institution");
	}

	/**
	 * 접수 상태(P-01)는 저장하지 않고 조회 때 계산한다. 시작·종료일 중 하나만 있으면 같은 날로 본다.
	 * 둘 다 없으면 unknown(E-05).
	 */
	private static final String BASE_CTE = """
			WITH n AS (
			  SELECT notice.*,
			         coalesce(rcept_start, rcept_end) AS s_start,
			         coalesce(rcept_end, rcept_start) AS s_end,
			         CASE WHEN rcept_start IS NULL AND rcept_end IS NULL THEN 'unknown'
			              WHEN :today < coalesce(rcept_start, rcept_end) THEN 'upcoming'
			              WHEN :today <= coalesce(rcept_end, rcept_start) THEN 'open'
			              ELSE 'closed' END AS status
			  FROM notice
			  WHERE removed_at IS NULL
			)
			""";

	private final JdbcClient jdbc;
	private final Clock clock;

	NoticeRepository(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	NoticeListResponse list(NoticeFilter f) {
		LocalDate today = LocalDate.now(clock);

		int total = count(f, today, Set.of(), false);
		List<Item> items = f.limit() == 0 ? List.of() : items(f, today);

		int noInfoExcluded = f.hasSupplyOrHouseKind() ? count(f, today, Set.of("supply", "house_kind"), true) : 0;
		List<Relaxation> relaxations = total == 0 ? relaxations(f, today) : List.of();
		return new NoticeListResponse(total, f.offset(), items, noInfoExcluded, relaxations, lastCrawl());
	}

	// ---- 건수 ----

	private int count(NoticeFilter f, LocalDate today, Set<String> drop, boolean onlyNoInfo) {
		Where w = where(f, today, drop, onlyNoInfo);
		String sql = BASE_CTE + "SELECT count(*) FROM n WHERE " + w.sql;
		return bind(jdbc.sql(sql), w.params).query(Integer.class).single();
	}

	private List<Relaxation> relaxations(NoticeFilter f, LocalDate today) {
		List<Relaxation> out = new ArrayList<>();
		for (String key : DROPPABLE) {
			if (!applied(f, key)) {
				continue;
			}
			int n = count(f, today, Set.of(key), false);
			if (n > 0) {
				out.add(new Relaxation(key, n));
			}
		}
		out.sort(Comparator.comparingInt(Relaxation::total).reversed());
		return out.size() > 2 ? out.subList(0, 2) : out;
	}

	/** 기본값이 아닌 조건만 뺄 의미가 있다. 접수 상태는 마감 공고를 넣는 제안이 되므로 closed가 없을 때만. */
	private static boolean applied(NoticeFilter f, String key) {
		return switch (key) {
			case "status" -> !f.status().contains("closed");
			case "region" -> !f.region().isEmpty();
			case "supply" -> !f.supply().isEmpty();
			case "price" -> f.priceMin() != null || f.priceMax() != null;
			case "area" -> !f.area().isEmpty();
			case "notice_type" -> !f.noticeType().isEmpty();
			case "house_kind" -> !f.houseKind().isEmpty();
			default -> false;
		};
	}

	// ---- 목록 ----

	private List<Item> items(NoticeFilter f, LocalDate today) {
		Where w = where(f, today, Set.of(), false);
		String sums = String.join(", ", SUPPLY_COLUMNS.values().stream()
				.map(c -> "sum(" + c + ") AS " + c).toList());
		String sql = BASE_CTE + """
				SELECT n.house_manage_no, n.name, n.notice_type, n.house_kind, n.region, n.address,
				       n.status, n.s_start, n.s_end, n.winner_on, n.total_households,
				       (n.last_changed_at IS NOT NULL AND n.last_changed_at >= :now - interval '7 days') AS is_corrected,
				       a.price_min, a.price_max, a.area_min, a.area_max, a.sp_newlywed, a.sp_first_home,
				       a.sp_multi_child, a.sp_newborn, a.sp_elderly, a.sp_youth, a.sp_institution
				FROM n
				LEFT JOIN LATERAL (
				  SELECT min(top_price_man) AS price_min, max(top_price_man) AS price_max,
				         min(exclusive_area) AS area_min, max(exclusive_area) AS area_max, %s
				  FROM notice_unit u WHERE u.house_manage_no = n.house_manage_no
				) a ON true
				WHERE %s
				ORDER BY %s
				LIMIT :limit OFFSET :offset
				""".formatted(sums, w.sql, orderBy(f));
		return bind(jdbc.sql(sql), w.params)
				.param("limit", f.limit())
				.param("offset", f.offset())
				.query((rs, i) -> toItem(rs, today))
				.list();
	}

	/**
	 * deadline: 접수 중(종료일 가까운 순) → 접수 예정(시작일 가까운 순) → 일정 확인 필요 → 마감(종료일 최근 순).
	 * latest: 공고일 내림차순. 둘 다 번호 내림차순으로 고정해 offset 페이지가 겹치지 않게 한다.
	 */
	private static String orderBy(NoticeFilter f) {
		if (f.sort().equals("latest")) {
			return "n.announced_on DESC, n.house_manage_no DESC";
		}
		return """
				CASE n.status WHEN 'open' THEN 0 WHEN 'upcoming' THEN 1 WHEN 'unknown' THEN 2 ELSE 3 END,
				CASE n.status WHEN 'open' THEN n.s_end WHEN 'upcoming' THEN n.s_start END ASC NULLS LAST,
				CASE n.status WHEN 'closed' THEN n.s_end END DESC NULLS LAST,
				n.house_manage_no DESC""";
	}

	private Item toItem(ResultSet rs, LocalDate today) throws SQLException {
		String status = rs.getString("status");
		LocalDate start = rs.getObject("s_start", LocalDate.class);
		LocalDate end = rs.getObject("s_end", LocalDate.class);
		Integer dDay = switch (status) {
			case "upcoming" -> (int) ChronoUnit.DAYS.between(today, start);
			case "open" -> (int) ChronoUnit.DAYS.between(today, end);
			default -> null;
		};
		List<SupplyTag> tags = new ArrayList<>();
		for (Map.Entry<String, String> e : SUPPLY_COLUMNS.entrySet()) {
			int households = rs.getInt(e.getValue());
			if (households > 0) {
				tags.add(new SupplyTag(e.getKey(), households));
			}
		}
		tags.sort(Comparator.comparingInt(SupplyTag::households).reversed());
		Double areaMin = toDouble(rs.getBigDecimal("area_min"));
		Double areaMax = toDouble(rs.getBigDecimal("area_max"));
		return new Item(
				rs.getString("house_manage_no"),
				rs.getString("name"),
				rs.getString("notice_type"),
				rs.getString("house_kind"),
				rs.getString("region"),
				District.of(rs.getString("address")),
				status,
				dDay,
				rs.getObject("s_start", LocalDate.class),
				rs.getObject("s_end", LocalDate.class),
				rs.getObject("winner_on", LocalDate.class),
				rs.getBoolean("is_corrected"),
				rs.getObject("price_min", Integer.class),
				rs.getObject("price_max", Integer.class),
				areaMin,
				areaMax,
				rs.getObject("total_households", Integer.class),
				tags);
	}

	private static Double toDouble(java.math.BigDecimal v) {
		return v == null ? null : v.doubleValue();
	}

	// ---- 수집 상태 ----

	private LastCrawl lastCrawl() {
		List<OffsetDateTime> finished = jdbc.sql(
				"SELECT finished_at FROM crawl_run WHERE status = 'success' AND finished_at IS NOT NULL "
						+ "ORDER BY finished_at DESC LIMIT 1")
				.query(OffsetDateTime.class).list();
		if (finished.isEmpty()) {
			return new LastCrawl(null, true);
		}
		OffsetDateTime at = finished.get(0);
		boolean stale = Duration.between(at.toInstant(), clock.instant()).compareTo(Duration.ofHours(24)) > 0;
		return new LastCrawl(at.withOffsetSameInstant(clock.getZone().getRules().getOffset(at.toInstant())), stale);
	}

	// ---- WHERE ----

	private record Where(String sql, Map<String, Object> params) {
	}

	/**
	 * @param drop       무시할 조건 이름. price·area·supply는 같은 주택형 EXISTS 안에서 각각 빠진다.
	 * @param onlyNoInfo true면 무순위·재공급(특별공급·주택 구분 정보가 없는 공고)만 센다(P-04)
	 */
	private Where where(NoticeFilter f, LocalDate today, Set<String> drop, boolean onlyNoInfo) {
		List<String> conds = new ArrayList<>();
		Map<String, Object> p = new HashMap<>();
		p.put("today", today);
		p.put("now", OffsetDateTime.now(clock));

		// 마감 공고는 접수 종료 1년 이내만(D-02)
		conds.add("(n.status <> 'closed' OR n.s_end >= :closedFrom)");
		p.put("closedFrom", today.minusYears(1));

		if (!drop.contains("status")) {
			List<String> statuses = new ArrayList<>(f.status());
			if (statuses.contains("upcoming")) {
				statuses.add("unknown"); // 일정 확인 필요는 접수 예정 뒤에 붙는다(E-05)
			}
			conds.add("n.status IN (:statuses)");
			p.put("statuses", statuses);
		}
		if (f.q() != null) {
			conds.add("regexp_replace(n.name, '[\\s()（）]', '', 'g') ILIKE :q ESCAPE '\\'");
			p.put("q", "%" + escapeLike(f.q().replaceAll("[\\s()（）]", "")) + "%");
		}
		if (!f.region().isEmpty() && !drop.contains("region")) {
			conds.add("n.region IN (:regions)");
			p.put("regions", f.region());
		}
		if (onlyNoInfo) {
			conds.add("n.notice_type IN ('no_rank', 'resupply')");
		}
		if (!f.noticeType().isEmpty() && !drop.contains("notice_type")) {
			conds.add("n.notice_type IN (:noticeTypes)");
			p.put("noticeTypes", f.noticeType());
		}
		if (!f.houseKind().isEmpty() && !drop.contains("house_kind")) {
			conds.add("n.house_kind IN (:houseKinds)");
			p.put("houseKinds", f.houseKind());
		}

		// 주택형 단위 조건은 한 주택형이 모두 만족해야 한다(F3-9)
		List<String> unit = new ArrayList<>();
		if (!f.supply().isEmpty() && !drop.contains("supply")) {
			unit.add("(" + String.join(" OR ", f.supply().stream()
					.map(s -> "u." + SUPPLY_COLUMNS.get(s) + " > 0").toList()) + ")");
		}
		if (!drop.contains("price")) {
			if (f.priceMin() != null) {
				unit.add("u.top_price_man >= :priceMin");
				p.put("priceMin", f.priceMin());
			}
			if (f.priceMax() != null) {
				unit.add("u.top_price_man <= :priceMax");
				p.put("priceMax", f.priceMax());
			}
		}
		if (!f.area().isEmpty() && !drop.contains("area")) {
			unit.add("(" + String.join(" OR ", f.area().stream().map(NoticeRepository::areaCondition).toList()) + ")");
		}
		if (!unit.isEmpty()) {
			conds.add("EXISTS (SELECT 1 FROM notice_unit u WHERE u.house_manage_no = n.house_manage_no AND "
					+ String.join(" AND ", unit) + ")");
		}
		return new Where(String.join(" AND ", conds), p);
	}

	private static String areaCondition(String code) {
		return switch (code) {
			case "le60" -> "u.exclusive_area <= 60";
			case "60to85" -> "(u.exclusive_area > 60 AND u.exclusive_area <= 85)";
			default -> "u.exclusive_area > 85";
		};
	}

	private static String escapeLike(String s) {
		return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Map<String, Object> params) {
		return spec.params(params);
	}
}
