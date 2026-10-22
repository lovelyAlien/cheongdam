package app.cheongdam.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** API.md 3장의 상세 응답. 필드 이름은 snake_case로 직렬화된다. */
public record NoticeDetailResponse(
		Header header,
		Summary summary,
		List<ScheduleRow> schedule,
		List<Unit> units,
		Extraction extraction,
		List<Change> changes) {

	public record Header(
			String houseManageNo,
			String name,
			String noticeType,
			String houseKind,
			String region,
			String address,
			String status,
			Integer dDay,
			boolean isCorrected,
			int correctionCount,
			OffsetDateTime lastCheckedAt,
			boolean removed,
			String noticeUrl,
			String homepageUrl,
			String phone) {
	}

	public record Summary(
			Integer totalHouseholds,
			Integer specialHouseholds,
			Integer generalHouseholds,
			Integer priceMinMan,
			Integer priceMaxMan,
			String moveInYm) {
	}

	public record ScheduleRow(String stage, String area, LocalDate startOn, LocalDate endOn, String state) {
	}

	public record Unit(
			String modelNo,
			String houseTy,
			BigDecimal exclusiveArea,
			BigDecimal supplyArea,
			Integer topPriceMan,
			Integer generalCount,
			Integer specialCount,
			Map<String, Integer> specialByType) {
	}

	public record Extraction(String status, boolean reprocessing, Constraints constraints, Conditions conditions) {
	}

	public record Constraints(List<Item> items, List<String> missingCodes) {
	}

	public record Conditions(List<SupplyType> supplyTypes, List<Item> items) {
	}

	public record SupplyType(String code, Integer households) {
	}

	public record Item(
			long id,
			int category,
			String code,
			List<String> targets,
			String text,
			boolean isGeneralRule,
			String status,
			List<Evidence> evidence) {
	}

	public record Evidence(int page, String quote, boolean verified) {
	}

	public record Change(int version, OffsetDateTime detectedAt, List<ChangeItem> items) {
	}

	public record ChangeItem(String field, String before, String after) {
	}
}
