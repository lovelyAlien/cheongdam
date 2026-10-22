package app.cheongdam.repository;

import app.cheongdam.domain.Notice;
import app.cheongdam.domain.NoticeUnit;
import app.cheongdam.domain.SpecialSupply;
import app.cheongdam.dto.NoticeFilter;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Nulls;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.domain.Specification;

/**
 * 공고 목록의 동적 조건과 정렬. 접수 상태(P-01)는 저장하지 않고 오늘 날짜와 접수 시작·종료일로 계산한다.
 * 시작·종료일 중 하나만 있으면 같은 날로 보고, 둘 다 없으면 unknown(E-05).
 */
public final class NoticeSpecifications {

	private NoticeSpecifications() {
	}

	/**
	 * @param drop       무시할 조건 이름. price·area·supply는 같은 주택형 EXISTS 안에서 각각 빠진다.
	 * @param onlyNoInfo true면 무순위·재공급(특별공급·주택 구분 정보가 없는 공고)만 센다(P-04)
	 */
	public static Specification<Notice> matching(NoticeFilter f, LocalDate today, Set<String> drop,
			boolean onlyNoInfo) {
		return (root, query, cb) -> {
			List<Predicate> ps = new ArrayList<>();
			ps.add(cb.isNull(root.get("removedAt")));

			// 마감 공고는 접수 종료 1년 이내만(D-02)
			Expression<LocalDate> end = end(root, cb);
			ps.add(cb.or(cb.isNull(end), cb.greaterThanOrEqualTo(end, today.minusYears(1))));

			if (!drop.contains("status")) {
				Set<String> statuses = new LinkedHashSet<>(f.status());
				if (statuses.contains("upcoming")) {
					statuses.add("unknown"); // 일정 확인 필요는 접수 예정 뒤에 붙는다(E-05)
				}
				ps.add(cb.or(statuses.stream().map(s -> statusIs(s, root, cb, today)).toArray(Predicate[]::new)));
			}
			if (f.q() != null) {
				ps.add(nameContains(f.q(), root, cb));
			}
			if (!f.region().isEmpty() && !drop.contains("region")) {
				ps.add(root.get("region").in(f.region()));
			}
			if (onlyNoInfo) {
				ps.add(root.get("noticeType").in("no_rank", "resupply"));
			}
			if (!f.noticeType().isEmpty() && !drop.contains("notice_type")) {
				ps.add(root.get("noticeType").in(f.noticeType()));
			}
			if (!f.houseKind().isEmpty() && !drop.contains("house_kind")) {
				ps.add(root.get("houseKind").in(f.houseKind()));
			}

			// 주택형 단위 조건은 한 주택형이 모두 만족해야 한다(F3-9)
			Subquery<Integer> units = query.subquery(Integer.class);
			Root<NoticeUnit> u = units.from(NoticeUnit.class);
			List<Predicate> up = new ArrayList<>();
			if (!f.supply().isEmpty() && !drop.contains("supply")) {
				up.add(cb.or(f.supply().stream()
						.map(code -> cb.greaterThan(u.<Integer>get(SpecialSupply.ofCode(code).orElseThrow().field()), 0))
						.toArray(Predicate[]::new)));
			}
			if (!drop.contains("price")) {
				if (f.priceMin() != null) {
					up.add(cb.greaterThanOrEqualTo(u.<Integer>get("topPriceMan"), f.priceMin()));
				}
				if (f.priceMax() != null) {
					up.add(cb.lessThanOrEqualTo(u.<Integer>get("topPriceMan"), f.priceMax()));
				}
			}
			if (!f.area().isEmpty() && !drop.contains("area")) {
				up.add(cb.or(f.area().stream().map(code -> areaIs(code, u, cb)).toArray(Predicate[]::new)));
			}
			if (!up.isEmpty()) {
				up.add(cb.equal(u.get("id").get("houseManageNo"), root.get("houseManageNo")));
				units.select(cb.literal(1)).where(up.toArray(Predicate[]::new));
				ps.add(cb.exists(units));
			}
			return cb.and(ps.toArray(Predicate[]::new));
		};
	}

	/**
	 * deadline: 접수 중(종료일 가까운 순) → 접수 예정(시작일 가까운 순) → 일정 확인 필요 → 마감(종료일 최근 순).
	 * latest: 공고일 내림차순. 둘 다 번호 내림차순으로 고정해 offset 페이지가 겹치지 않게 한다.
	 * 건수 쿼리에서는 Spring Data가 정렬을 지운다.
	 */
	public static Specification<Notice> orderedBy(String sort, LocalDate today) {
		return (root, query, cb) -> {
			List<Order> orders = new ArrayList<>();
			if (sort.equals("latest")) {
				orders.add(cb.desc(root.get("announcedOn")));
			} else {
				Predicate open = statusIs("open", root, cb, today);
				Predicate upcoming = statusIs("upcoming", root, cb, today);
				Predicate unknown = statusIs("unknown", root, cb, today);
				Predicate closed = statusIs("closed", root, cb, today);
				Expression<Integer> rank = cb.<Integer>selectCase()
						.when(open, cb.literal(0)).when(upcoming, cb.literal(1)).when(unknown, cb.literal(2))
						.otherwise(cb.literal(3));
				Expression<LocalDate> soonest = cb.<LocalDate>selectCase()
						.when(open, end(root, cb)).when(upcoming, start(root, cb)).otherwise(cb.nullLiteral(LocalDate.class));
				Expression<LocalDate> latestClosed = cb.<LocalDate>selectCase()
						.when(closed, end(root, cb)).otherwise(cb.nullLiteral(LocalDate.class));
				orders.add(cb.asc(rank));
				orders.add(cb.asc(soonest, Nulls.LAST));
				orders.add(cb.desc(latestClosed, Nulls.LAST));
			}
			orders.add(cb.desc(root.get("houseManageNo")));
			query.orderBy(orders);
			return null;
		};
	}

	private static Expression<LocalDate> start(Root<Notice> root, CriteriaBuilder cb) {
		return cb.coalesce(root.<LocalDate>get("rceptStart"), root.<LocalDate>get("rceptEnd"));
	}

	private static Expression<LocalDate> end(Root<Notice> root, CriteriaBuilder cb) {
		return cb.coalesce(root.<LocalDate>get("rceptEnd"), root.<LocalDate>get("rceptStart"));
	}

	private static Predicate statusIs(String status, Root<Notice> root, CriteriaBuilder cb, LocalDate today) {
		return switch (status) {
			case "upcoming" -> cb.greaterThan(start(root, cb), today);
			case "open" -> cb.and(cb.lessThanOrEqualTo(start(root, cb), today),
					cb.greaterThanOrEqualTo(end(root, cb), today));
			case "closed" -> cb.lessThan(end(root, cb), today);
			default -> cb.and(cb.isNull(root.get("rceptStart")), cb.isNull(root.get("rceptEnd")));
		};
	}

	/** 공백·괄호를 뺀 이름에서 대소문자 구분 없이 찾는다. */
	private static Predicate nameContains(String q, Root<Notice> root, CriteriaBuilder cb) {
		Expression<String> normalized = cb.function("regexp_replace", String.class, root.<String>get("name"),
				cb.literal(IGNORED_IN_SEARCH), cb.literal(""), cb.literal("g"));
		String pattern = "%" + escapeLike(q.replaceAll(IGNORED_IN_SEARCH, "")).toLowerCase() + "%";
		return cb.like(cb.lower(normalized), pattern, '\\');
	}

	private static final String IGNORED_IN_SEARCH = "[\\s()（）]";

	private static Predicate areaIs(String code, Root<NoticeUnit> u, CriteriaBuilder cb) {
		Expression<BigDecimal> area = u.get("exclusiveArea");
		BigDecimal sixty = BigDecimal.valueOf(60);
		BigDecimal eightyFive = BigDecimal.valueOf(85);
		return switch (code) {
			case "le60" -> cb.lessThanOrEqualTo(area, sixty);
			case "60to85" -> cb.and(cb.greaterThan(area, sixty), cb.lessThanOrEqualTo(area, eightyFive));
			default -> cb.greaterThan(area, eightyFive);
		};
	}

	private static String escapeLike(String s) {
		return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
