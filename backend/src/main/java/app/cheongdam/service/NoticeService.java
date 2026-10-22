package app.cheongdam.service;

import app.cheongdam.config.ClockConfig;
import app.cheongdam.domain.CrawlRun;
import app.cheongdam.domain.Notice;
import app.cheongdam.domain.NoticeUnit;
import app.cheongdam.domain.SpecialSupply;
import app.cheongdam.dto.NoticeFilter;
import app.cheongdam.dto.NoticeListResponse;
import app.cheongdam.dto.NoticeListResponse.Item;
import app.cheongdam.dto.NoticeListResponse.LastCrawl;
import app.cheongdam.dto.NoticeListResponse.Relaxation;
import app.cheongdam.dto.NoticeListResponse.SupplyTag;
import app.cheongdam.repository.CrawlRunRepository;
import app.cheongdam.repository.NoticeRepository;
import app.cheongdam.repository.NoticeSpecifications;
import app.cheongdam.repository.NoticeUnitRepository;
import app.cheongdam.util.District;
import app.cheongdam.util.NoticeStatus;
import app.cheongdam.util.OffsetLimitRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공고 목록(S-01). 조건에 맞는 공고를 찾고, 결과가 없으면 어떤 조건을 빼면 몇 건인지 알려 준다. */
@Service
@Transactional(readOnly = true)
public class NoticeService {

	/** 필터를 하나씩 뺐을 때의 건수를 셀 수 있는 조건 이름(API.md 2장 relaxations). */
	static final List<String> DROPPABLE = List.of("status", "region", "supply", "price", "area", "notice_type",
			"house_kind");

	private final NoticeRepository noticeRepository;
	private final NoticeUnitRepository unitRepository;
	private final CrawlRunRepository crawlRunRepository;
	private final Clock clock;

	public NoticeService(NoticeRepository noticeRepository, NoticeUnitRepository unitRepository,
			CrawlRunRepository crawlRunRepository, Clock clock) {
		this.noticeRepository = noticeRepository;
		this.unitRepository = unitRepository;
		this.crawlRunRepository = crawlRunRepository;
		this.clock = clock;
	}

	public NoticeListResponse list(NoticeFilter f) {
		LocalDate today = LocalDate.now(clock);
		OffsetDateTime now = OffsetDateTime.now(clock);

		var matching = NoticeSpecifications.matching(f, today, Set.of(), false);
		int total;
		List<Item> items;
		if (f.limit() == 0) {
			total = (int) noticeRepository.count(matching);
			items = List.of();
		} else {
			Page<Notice> page = noticeRepository.findAll(matching.and(NoticeSpecifications.orderedBy(f.sort(), today)),
					new OffsetLimitRequest(f.offset(), f.limit()));
			total = (int) page.getTotalElements();
			items = items(page.getContent(), today, now);
		}

		int noInfoExcluded = f.hasSupplyOrHouseKind()
				? count(f, today, Set.of("supply", "house_kind"), true) : 0;
		List<Relaxation> relaxations = total == 0 ? relaxations(f, today) : List.of();
		return new NoticeListResponse(total, f.offset(), items, noInfoExcluded, relaxations, lastCrawl());
	}

	// ---- 건수 ----

	private int count(NoticeFilter f, LocalDate today, Set<String> drop, boolean onlyNoInfo) {
		return (int) noticeRepository.count(NoticeSpecifications.matching(f, today, drop, onlyNoInfo));
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

	private List<Item> items(List<Notice> notices, LocalDate today, OffsetDateTime now) {
		Map<String, List<NoticeUnit>> unitsByNotice = unitRepository
				.findByIdHouseManageNoIn(notices.stream().map(Notice::getHouseManageNo).toList()).stream()
				.collect(Collectors.groupingBy(u -> u.getId().getHouseManageNo()));
		return notices.stream()
				.map(n -> toItem(n, unitsByNotice.getOrDefault(n.getHouseManageNo(), List.of()), today, now))
				.toList();
	}

	private Item toItem(Notice n, List<NoticeUnit> units, LocalDate today, OffsetDateTime now) {
		LocalDate start = n.getRceptStart() != null ? n.getRceptStart() : n.getRceptEnd();
		LocalDate end = n.getRceptEnd() != null ? n.getRceptEnd() : n.getRceptStart();
		String status = NoticeStatus.of(n.getRceptStart(), n.getRceptEnd(), today);
		boolean corrected = n.getLastChangedAt() != null && !n.getLastChangedAt().isBefore(now.minusDays(7));

		List<Integer> prices = units.stream().map(NoticeUnit::getTopPriceMan).filter(Objects::nonNull).toList();
		List<BigDecimal> areas = units.stream().map(NoticeUnit::getExclusiveArea).filter(Objects::nonNull).toList();
		return new Item(
				n.getHouseManageNo(),
				n.getName(),
				n.getNoticeType(),
				n.getHouseKind(),
				n.getRegion(),
				District.of(n.getAddress()),
				status,
				NoticeStatus.dDay(status, start, end, today),
				start,
				end,
				n.getWinnerOn(),
				corrected,
				prices.stream().min(Integer::compare).orElse(null),
				prices.stream().max(Integer::compare).orElse(null),
				areas.stream().min(BigDecimal::compareTo).map(BigDecimal::doubleValue).orElse(null),
				areas.stream().max(BigDecimal::compareTo).map(BigDecimal::doubleValue).orElse(null),
				n.getTotalHouseholds(),
				supplyTags(units));
	}

	/** 특별공급 유형별 세대수를 주택형 전체에서 더해, 1세대 이상인 유형만 많은 순으로 태그로 만든다. */
	private static List<SupplyTag> supplyTags(List<NoticeUnit> units) {
		List<SupplyTag> tags = new ArrayList<>();
		for (SpecialSupply supply : SpecialSupply.values()) {
			int households = units.stream().map(supply::householdsOf).filter(Objects::nonNull)
					.mapToInt(Integer::intValue).sum();
			if (households > 0) {
				tags.add(new SupplyTag(supply.code(), households));
			}
		}
		tags.sort(Comparator.comparingInt(SupplyTag::households).reversed());
		return tags;
	}

	// ---- 수집 상태 ----

	private LastCrawl lastCrawl() {
		return crawlRunRepository.findFirstByStatusAndFinishedAtIsNotNullOrderByFinishedAtDesc("success")
				.map(CrawlRun::getFinishedAt)
				.map(at -> new LastCrawl(ClockConfig.kst(at),
						Duration.between(at.toInstant(), clock.instant()).compareTo(Duration.ofHours(24)) > 0))
				.orElseGet(() -> new LastCrawl(null, true));
	}
}
