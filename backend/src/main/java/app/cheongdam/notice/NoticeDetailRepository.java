package app.cheongdam.notice;

import app.cheongdam.common.ApiException;
import app.cheongdam.common.ClockConfig;
import app.cheongdam.notice.NoticeDetailResponse.Change;
import app.cheongdam.notice.NoticeDetailResponse.ChangeItem;
import app.cheongdam.notice.NoticeDetailResponse.Conditions;
import app.cheongdam.notice.NoticeDetailResponse.Constraints;
import app.cheongdam.notice.NoticeDetailResponse.Evidence;
import app.cheongdam.notice.NoticeDetailResponse.Extraction;
import app.cheongdam.notice.NoticeDetailResponse.Header;
import app.cheongdam.notice.NoticeDetailResponse.Item;
import app.cheongdam.notice.NoticeDetailResponse.ScheduleRow;
import app.cheongdam.notice.NoticeDetailResponse.Summary;
import app.cheongdam.notice.NoticeDetailResponse.SupplyType;
import app.cheongdam.notice.NoticeDetailResponse.Unit;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class NoticeDetailRepository {

	/** 블록 B 행 순서(SCREENS 블록 B). */
	static final List<String> CONSTRAINT_CODES = List.of("rewin_limit", "apply_restriction", "ineligible_limit",
			"resale_limit", "residence_duty", "payment", "duplicate_apply");

	private static final List<String> STAGE_ORDER = List.of("special", "rank1", "rank2", "no_rank", "general",
			"winner", "contract");
	private static final List<String> AREA_ORDER = List.of("all", "local", "gyeonggi", "other");
	/** 특별공급 유형 코드 → notice_unit 컬럼. sp_etc는 유형 칩에 쓰지 않는다. */
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
	private static final int QUOTE_MAX = 300;

	private final JdbcClient jdbc;
	private final Clock clock;

	NoticeDetailRepository(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	NoticeDetailResponse find(String no) {
		LocalDate today = LocalDate.now(clock);
		NoticeRow n = jdbc.sql("""
				SELECT house_manage_no, name, notice_type, house_kind, region, address, rcept_start, rcept_end,
				       move_in_ym, notice_url, homepage_url, phone, current_version, last_checked_at,
				       last_changed_at, removed_at, summary_status
				FROM notice WHERE house_manage_no = :no
				""")
				.param("no", no)
				.query((rs, i) -> new NoticeRow(
						rs.getString("house_manage_no"), rs.getString("name"), rs.getString("notice_type"),
						rs.getString("house_kind"), rs.getString("region"), rs.getString("address"),
						rs.getObject("rcept_start", LocalDate.class), rs.getObject("rcept_end", LocalDate.class),
						rs.getString("move_in_ym"), rs.getString("notice_url"), rs.getString("homepage_url"),
						rs.getString("phone"), rs.getInt("current_version"),
						rs.getObject("last_checked_at", OffsetDateTime.class),
						rs.getObject("last_changed_at", OffsetDateTime.class),
						rs.getObject("removed_at", OffsetDateTime.class), rs.getString("summary_status")))
				.optional()
				.orElseThrow(() -> ApiException.notFound("찾는 공고가 없어요."));

		List<Unit> units = units(no, n.noticeType());
		return new NoticeDetailResponse(
				header(n, today),
				summary(n, units),
				schedule(no, today),
				units,
				extraction(n, units),
				changes(no));
	}

	// ---- 헤더·요약 ----

	private Header header(NoticeRow n, LocalDate today) {
		String status = NoticeStatus.of(n.rceptStart(), n.rceptEnd(), today);
		LocalDate start = n.rceptStart() != null ? n.rceptStart() : n.rceptEnd();
		LocalDate end = n.rceptEnd() != null ? n.rceptEnd() : n.rceptStart();
		boolean corrected = n.lastChangedAt() != null
				&& n.lastChangedAt().isAfter(OffsetDateTime.now(clock).minusDays(7));
		return new Header(n.no(), n.name(), n.noticeType(), n.houseKind(), n.region(), n.address(), status,
				NoticeStatus.dDay(status, start, end, today), corrected, n.currentVersion() - 1, ClockConfig.kst(n.lastCheckedAt()),
				n.removedAt() != null, n.noticeUrl(), n.homepageUrl(), n.phone());
	}

	private Summary summary(NoticeRow n, List<Unit> units) {
		Integer special = sum(units.stream().map(Unit::specialCount).toList());
		Integer general = sum(units.stream().map(Unit::generalCount).toList());
		Integer total = jdbc.sql("SELECT total_households FROM notice WHERE house_manage_no = :no")
				.param("no", n.no()).query(Integer.class).optional().orElse(null);
		var prices = units.stream().map(Unit::topPriceMan).filter(p -> p != null).toList();
		return new Summary(total, special, general,
				prices.stream().min(Integer::compare).orElse(null),
				prices.stream().max(Integer::compare).orElse(null), n.moveInYm());
	}

	private static Integer sum(List<Integer> values) {
		var present = values.stream().filter(v -> v != null).toList();
		return present.isEmpty() ? null : present.stream().mapToInt(Integer::intValue).sum();
	}

	// ---- 일정·주택형 ----

	private List<ScheduleRow> schedule(String no, LocalDate today) {
		List<ScheduleRow> rows = jdbc.sql("""
				SELECT stage, area, start_on, end_on FROM notice_schedule WHERE house_manage_no = :no
				""")
				.param("no", no)
				.query((rs, i) -> {
					LocalDate s = rs.getObject("start_on", LocalDate.class);
					LocalDate e = rs.getObject("end_on", LocalDate.class);
					return new ScheduleRow(rs.getString("stage"), rs.getString("area"), s, e, NoticeStatus.stageState(s, e, today));
				}).list();
		return rows.stream()
				.sorted(Comparator.comparingInt((ScheduleRow r) -> STAGE_ORDER.indexOf(r.stage()))
						.thenComparingInt(r -> AREA_ORDER.indexOf(r.area())))
				.toList();
	}

	private List<Unit> units(String no, String noticeType) {
		boolean noTypeInfo = !noticeType.equals("general"); // 무순위·재공급은 유형별 세대수가 없다(P-04)
		return jdbc.sql("""
				SELECT model_no, house_ty, exclusive_area, supply_area, top_price_man, general_count, special_count,
				       sp_newlywed, sp_first_home, sp_multi_child, sp_newborn, sp_elderly, sp_youth,
				       sp_institution, sp_etc
				FROM notice_unit WHERE house_manage_no = :no ORDER BY exclusive_area, model_no
				""")
				.param("no", no)
				.query((rs, i) -> {
					Map<String, Integer> byType = null;
					if (!noTypeInfo) {
						byType = new LinkedHashMap<>();
						for (Map.Entry<String, String> e : SUPPLY_COLUMNS.entrySet()) {
							byType.put(e.getKey(), rs.getObject(e.getValue(), Integer.class));
						}
						byType.put("etc", rs.getObject("sp_etc", Integer.class));
					}
					return new Unit(rs.getString("model_no"), rs.getString("house_ty"),
							rs.getBigDecimal("exclusive_area"), rs.getBigDecimal("supply_area"),
							rs.getObject("top_price_man", Integer.class), rs.getObject("general_count", Integer.class),
							rs.getObject("special_count", Integer.class), byType);
				}).list();
	}

	// ---- 블록 B·C ----

	private Extraction extraction(NoticeRow n, List<Unit> units) {
		String status = n.summaryStatus();
		// 현재 버전이 아직 추출 전이어도 직전에 끝난 버전의 결과를 보여 준다(P-08)
		Integer version = jdbc.sql("""
				SELECT max(version) FROM extraction_run
				WHERE house_manage_no = :no AND status IN ('done','partial')
				""").param("no", n.no()).query(Integer.class).optional().orElse(null);
		if (version == null) {
			return new Extraction(status, false, new Constraints(List.of(), List.of()),
					new Conditions(supplyTypes(n, units), List.of()));
		}
		boolean reprocessing = version < n.currentVersion();
		List<Item> all = items(n.no(), version);
		List<Item> constraints = all.stream().filter(i -> i.category() == 6)
				.sorted(Comparator.comparingInt(i -> CONSTRAINT_CODES.indexOf(i.code())))
				.toList();
		var present = constraints.stream().map(Item::code).collect(Collectors.toSet());
		List<String> missing = CONSTRAINT_CODES.stream().filter(c -> !present.contains(c)).toList();
		List<Item> conditions = all.stream().filter(i -> i.category() <= 5).toList();
		// 재추출 대기 중(pending)이어도 보여 줄 이전 결과가 있으면 그 내용을 done으로 보여 준다
		return new Extraction(status.equals("pending") ? "done" : status, reprocessing,
				new Constraints(constraints, missing), new Conditions(supplyTypes(n, units), conditions));
	}

	private List<Item> items(String no, int version) {
		Map<Long, List<Evidence>> evidence = new LinkedHashMap<>();
		jdbc.sql("""
				SELECT e.item_id, e.page, e.quote, e.verified
				FROM item_evidence e JOIN extracted_item i ON i.id = e.item_id
				WHERE i.house_manage_no = :no AND i.version = :v ORDER BY e.item_id, e.id
				""")
				.param("no", no).param("v", version)
				.query((rs, i) -> {
					evidence.computeIfAbsent(rs.getLong("item_id"), k -> new ArrayList<>())
							.add(new Evidence(rs.getInt("page"), truncate(rs.getString("quote")), rs.getBoolean("verified")));
					return null;
				}).list();
		return jdbc.sql("""
				SELECT id, category, constraint_code, targets, requirement, is_general_rule, status
				FROM extracted_item WHERE house_manage_no = :no AND version = :v
				ORDER BY category, sort_order, id
				""")
				.param("no", no).param("v", version)
				.query((rs, i) -> toItem(rs, evidence)).list();
	}

	private static Item toItem(ResultSet rs, Map<Long, List<Evidence>> evidence) throws SQLException {
		long id = rs.getLong("id");
		Array targets = rs.getArray("targets");
		List<String> list = targets == null ? List.of() : Arrays.asList((String[]) targets.getArray());
		return new Item(id, rs.getInt("category"), rs.getString("constraint_code"), list, rs.getString("requirement"),
				rs.getBoolean("is_general_rule"), rs.getString("status"), evidence.getOrDefault(id, List.of()));
	}

	private static String truncate(String quote) {
		return quote.length() <= QUOTE_MAX ? quote : quote.substring(0, QUOTE_MAX) + "…";
	}

	/** 블록 C 유형 칩: 세대수 1 이상인 특별공급 유형 + 일반 1·2순위. 무순위·재공급은 하나(DATA 2장). */
	private List<SupplyType> supplyTypes(NoticeRow n, List<Unit> units) {
		if (!n.noticeType().equals("general")) {
			Integer total = jdbc.sql("SELECT total_households FROM notice WHERE house_manage_no = :no")
					.param("no", n.no()).query(Integer.class).optional().orElse(null);
			return List.of(new SupplyType(n.noticeType(), total));
		}
		List<SupplyType> out = new ArrayList<>();
		Integer general = sum(units.stream().map(Unit::generalCount).toList());
		out.add(new SupplyType("general_1st", general));
		out.add(new SupplyType("general_2nd", general));
		List<SupplyType> special = new ArrayList<>();
		for (String code : SUPPLY_COLUMNS.keySet()) {
			Integer total = sum(units.stream()
					.map(u -> u.specialByType() == null ? null : u.specialByType().get(code)).toList());
			if (total != null && total > 0) {
				special.add(new SupplyType(code, total));
			}
		}
		special.sort(Comparator.comparingInt((SupplyType s) -> s.households()).reversed());
		out.addAll(special);
		return out;
	}

	// ---- 정정 이력 ----

	private List<Change> changes(String no) {
		Map<Integer, List<ChangeItem>> items = new LinkedHashMap<>();
		jdbc.sql("SELECT version, field, before_value, after_value FROM notice_change WHERE house_manage_no = :no ORDER BY version DESC, id")
				.param("no", no)
				.query((rs, i) -> {
					items.computeIfAbsent(rs.getInt("version"), k -> new ArrayList<>())
							.add(new ChangeItem(rs.getString("field"), rs.getString("before_value"), rs.getString("after_value")));
					return null;
				}).list();
		return jdbc.sql("SELECT version, detected_at FROM notice_version WHERE house_manage_no = :no AND version > 1 ORDER BY version DESC")
				.param("no", no)
				.query((rs, i) -> new Change(rs.getInt("version"), ClockConfig.kst(rs.getObject("detected_at", OffsetDateTime.class)),
						items.getOrDefault(rs.getInt("version"), List.of())))
				.list();
	}

	private record NoticeRow(String no, String name, String noticeType, String houseKind, String region,
			String address, LocalDate rceptStart, LocalDate rceptEnd, String moveInYm, String noticeUrl,
			String homepageUrl, String phone, int currentVersion, OffsetDateTime lastCheckedAt,
			OffsetDateTime lastChangedAt, OffsetDateTime removedAt, String summaryStatus) {
	}
}
