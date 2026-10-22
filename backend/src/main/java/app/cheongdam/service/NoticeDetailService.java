package app.cheongdam.service;

import app.cheongdam.config.ClockConfig;
import app.cheongdam.domain.ExtractedItem;
import app.cheongdam.domain.ItemEvidence;
import app.cheongdam.domain.Notice;
import app.cheongdam.domain.NoticeChange;
import app.cheongdam.domain.NoticeSchedule;
import app.cheongdam.domain.NoticeUnit;
import app.cheongdam.domain.SpecialSupply;
import app.cheongdam.dto.NoticeDetailResponse;
import app.cheongdam.dto.NoticeDetailResponse.Change;
import app.cheongdam.dto.NoticeDetailResponse.ChangeItem;
import app.cheongdam.dto.NoticeDetailResponse.Conditions;
import app.cheongdam.dto.NoticeDetailResponse.Constraints;
import app.cheongdam.dto.NoticeDetailResponse.Evidence;
import app.cheongdam.dto.NoticeDetailResponse.Extraction;
import app.cheongdam.dto.NoticeDetailResponse.Header;
import app.cheongdam.dto.NoticeDetailResponse.Item;
import app.cheongdam.dto.NoticeDetailResponse.ScheduleRow;
import app.cheongdam.dto.NoticeDetailResponse.Summary;
import app.cheongdam.dto.NoticeDetailResponse.SupplyType;
import app.cheongdam.dto.NoticeDetailResponse.Unit;
import app.cheongdam.exception.ApiException;
import app.cheongdam.repository.ExtractedItemRepository;
import app.cheongdam.repository.ExtractionRunRepository;
import app.cheongdam.repository.ItemEvidenceRepository;
import app.cheongdam.repository.NoticeChangeRepository;
import app.cheongdam.repository.NoticeRepository;
import app.cheongdam.repository.NoticeScheduleRepository;
import app.cheongdam.repository.NoticeUnitRepository;
import app.cheongdam.repository.NoticeVersionRepository;
import app.cheongdam.util.NoticeStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공고 상세(S-03). 헤더·요약·일정·주택형·추출 결과·정정 이력을 한 응답으로 조립한다. */
@Service
@Transactional(readOnly = true)
public class NoticeDetailService {

	/** 블록 B 행 순서(SCREENS 블록 B). */
	static final List<String> CONSTRAINT_CODES = List.of("rewin_limit", "apply_restriction", "ineligible_limit",
			"resale_limit", "residence_duty", "payment", "duplicate_apply");

	private static final List<String> STAGE_ORDER = List.of("special", "rank1", "rank2", "no_rank", "general",
			"winner", "contract");
	private static final List<String> AREA_ORDER = List.of("all", "local", "gyeonggi", "other");
	private static final int QUOTE_MAX = 300;

	private final NoticeRepository noticeRepository;
	private final NoticeScheduleRepository scheduleRepository;
	private final NoticeUnitRepository unitRepository;
	private final NoticeVersionRepository versionRepository;
	private final NoticeChangeRepository changeRepository;
	private final ExtractionRunRepository extractionRunRepository;
	private final ExtractedItemRepository itemRepository;
	private final ItemEvidenceRepository evidenceRepository;
	private final Clock clock;

	public NoticeDetailService(NoticeRepository noticeRepository, NoticeScheduleRepository scheduleRepository,
			NoticeUnitRepository unitRepository, NoticeVersionRepository versionRepository,
			NoticeChangeRepository changeRepository, ExtractionRunRepository extractionRunRepository,
			ExtractedItemRepository itemRepository, ItemEvidenceRepository evidenceRepository, Clock clock) {
		this.noticeRepository = noticeRepository;
		this.scheduleRepository = scheduleRepository;
		this.unitRepository = unitRepository;
		this.versionRepository = versionRepository;
		this.changeRepository = changeRepository;
		this.extractionRunRepository = extractionRunRepository;
		this.itemRepository = itemRepository;
		this.evidenceRepository = evidenceRepository;
		this.clock = clock;
	}

	public NoticeDetailResponse find(String no) {
		LocalDate today = LocalDate.now(clock);
		Notice n = noticeRepository.findById(no).orElseThrow(() -> ApiException.notFound("찾는 공고가 없어요."));

		List<Unit> units = units(n);
		return new NoticeDetailResponse(
				header(n, today),
				summary(n, units),
				schedule(no, today),
				units,
				extraction(n, units),
				changes(no));
	}

	// ---- 헤더·요약 ----

	private Header header(Notice n, LocalDate today) {
		String status = NoticeStatus.of(n.getRceptStart(), n.getRceptEnd(), today);
		LocalDate start = n.getRceptStart() != null ? n.getRceptStart() : n.getRceptEnd();
		LocalDate end = n.getRceptEnd() != null ? n.getRceptEnd() : n.getRceptStart();
		boolean corrected = n.getLastChangedAt() != null
				&& n.getLastChangedAt().isAfter(OffsetDateTime.now(clock).minusDays(7));
		return new Header(n.getHouseManageNo(), n.getName(), n.getNoticeType(), n.getHouseKind(), n.getRegion(),
				n.getAddress(), status, NoticeStatus.dDay(status, start, end, today), corrected,
				n.getCurrentVersion() - 1, ClockConfig.kst(n.getLastCheckedAt()), n.getRemovedAt() != null,
				n.getNoticeUrl(), n.getHomepageUrl(), n.getPhone());
	}

	private Summary summary(Notice n, List<Unit> units) {
		var prices = units.stream().map(Unit::topPriceMan).filter(Objects::nonNull).toList();
		return new Summary(n.getTotalHouseholds(),
				sum(units.stream().map(Unit::specialCount).toList()),
				sum(units.stream().map(Unit::generalCount).toList()),
				prices.stream().min(Integer::compare).orElse(null),
				prices.stream().max(Integer::compare).orElse(null), n.getMoveInYm());
	}

	private static Integer sum(List<Integer> values) {
		var present = values.stream().filter(Objects::nonNull).toList();
		return present.isEmpty() ? null : present.stream().mapToInt(Integer::intValue).sum();
	}

	// ---- 일정·주택형 ----

	private List<ScheduleRow> schedule(String no, LocalDate today) {
		return scheduleRepository.findByIdHouseManageNo(no).stream()
				.map(s -> new ScheduleRow(s.getStage(), s.getArea(), s.getStartOn(), s.getEndOn(),
						NoticeStatus.stageState(s.getStartOn(), s.getEndOn(), today)))
				.sorted(Comparator.comparingInt((ScheduleRow r) -> STAGE_ORDER.indexOf(r.stage()))
						.thenComparingInt(r -> AREA_ORDER.indexOf(r.area())))
				.toList();
	}

	private List<Unit> units(Notice n) {
		boolean noTypeInfo = !n.getNoticeType().equals("general"); // 무순위·재공급은 유형별 세대수가 없다(P-04)
		return unitRepository.findByIdHouseManageNoOrderByExclusiveAreaAscIdModelNoAsc(n.getHouseManageNo()).stream()
				.map(u -> toUnit(u, noTypeInfo))
				.toList();
	}

	private static Unit toUnit(NoticeUnit u, boolean noTypeInfo) {
		Map<String, Integer> byType = null;
		if (!noTypeInfo) {
			byType = new LinkedHashMap<>();
			for (SpecialSupply supply : SpecialSupply.values()) {
				byType.put(supply.code(), supply.householdsOf(u));
			}
			byType.put("etc", u.getSpEtc());
		}
		return new Unit(u.getModelNo(), u.getHouseTy(), u.getExclusiveArea(), u.getSupplyArea(), u.getTopPriceMan(),
				u.getGeneralCount(), u.getSpecialCount(), byType);
	}

	// ---- 블록 B·C ----

	private Extraction extraction(Notice n, List<Unit> units) {
		String status = n.getSummaryStatus();
		// 현재 버전이 아직 추출 전이어도 직전에 끝난 버전의 결과를 보여 준다(P-08)
		Integer version = extractionRunRepository.findLatestFinishedVersion(n.getHouseManageNo()).orElse(null);
		if (version == null) {
			return new Extraction(status, false, new Constraints(List.of(), List.of()),
					new Conditions(supplyTypes(n, units), List.of()));
		}
		boolean reprocessing = version < n.getCurrentVersion();
		List<Item> all = items(n.getHouseManageNo(), version);
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
		List<ExtractedItem> items = itemRepository
				.findByHouseManageNoAndVersionOrderByCategoryAscSortOrderAscIdAsc(no, version);
		Map<Long, List<Evidence>> evidence = items.isEmpty() ? Map.of()
				: evidenceRepository.findByItemIds(items.stream().map(ExtractedItem::getId).toList()).stream()
						.collect(Collectors.groupingBy(e -> e.getItem().getId(), LinkedHashMap::new,
								Collectors.mapping(NoticeDetailService::toEvidence, Collectors.toList())));
		return items.stream().map(i -> toItem(i, evidence.getOrDefault(i.getId(), List.of()))).toList();
	}

	private static Item toItem(ExtractedItem i, List<Evidence> evidence) {
		List<String> targets = i.getTargets() == null ? List.of() : i.getTargets();
		return new Item(i.getId(), i.getCategory(), i.getConstraintCode(), targets, i.getRequirement(),
				i.isGeneralRule(), i.getStatus(), evidence);
	}

	private static Evidence toEvidence(ItemEvidence e) {
		return new Evidence(e.getPage(), truncate(e.getQuote()), e.isVerified());
	}

	private static String truncate(String quote) {
		return quote.length() <= QUOTE_MAX ? quote : quote.substring(0, QUOTE_MAX) + "…";
	}

	/** 블록 C 유형 칩: 세대수 1 이상인 특별공급 유형 + 일반 1·2순위. 무순위·재공급은 하나(DATA 2장). */
	private List<SupplyType> supplyTypes(Notice n, List<Unit> units) {
		if (!n.getNoticeType().equals("general")) {
			return List.of(new SupplyType(n.getNoticeType(), n.getTotalHouseholds()));
		}
		List<SupplyType> out = new ArrayList<>();
		Integer general = sum(units.stream().map(Unit::generalCount).toList());
		out.add(new SupplyType("general_1st", general));
		out.add(new SupplyType("general_2nd", general));
		List<SupplyType> special = new ArrayList<>();
		for (SpecialSupply supply : SpecialSupply.values()) {
			Integer total = sum(units.stream()
					.map(u -> u.specialByType() == null ? null : u.specialByType().get(supply.code())).toList());
			if (total != null && total > 0) {
				special.add(new SupplyType(supply.code(), total));
			}
		}
		special.sort(Comparator.comparingInt((SupplyType s) -> s.households()).reversed());
		out.addAll(special);
		return out;
	}

	// ---- 정정 이력 ----

	private List<Change> changes(String no) {
		Map<Integer, List<ChangeItem>> items = changeRepository.findByHouseManageNoOrderByVersionDescIdAsc(no).stream()
				.collect(Collectors.groupingBy(NoticeChange::getVersion, LinkedHashMap::new,
						Collectors.mapping(c -> new ChangeItem(c.getField(), c.getBeforeValue(), c.getAfterValue()),
								Collectors.toList())));
		return versionRepository.findByIdHouseManageNoAndIdVersionGreaterThanOrderByIdVersionDesc(no, 1).stream()
				.map(v -> new Change(v.getVersion(), ClockConfig.kst(v.getDetectedAt()),
						items.getOrDefault(v.getVersion(), List.of())))
				.toList();
	}
}
