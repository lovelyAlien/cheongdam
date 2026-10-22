package app.cheongdam.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** API.md 2장의 응답. 필드 이름은 snake_case로 직렬화된다. */
public record NoticeListResponse(
		int total,
		int offset,
		List<Item> items,
		int noInfoExcluded,
		List<Relaxation> relaxations,
		LastCrawl lastCrawl) {

	public record Item(
			String houseManageNo,
			String name,
			String noticeType,
			String houseKind,
			String region,
			String district,
			String status,
			Integer dDay,
			LocalDate rceptStart,
			LocalDate rceptEnd,
			LocalDate winnerOn,
			boolean isCorrected,
			Integer priceMinMan,
			Integer priceMaxMan,
			Double areaMin,
			Double areaMax,
			Integer totalHouseholds,
			List<SupplyTag> supplyTags) {
	}

	public record SupplyTag(String code, int households) {
	}

	public record Relaxation(String drop, int total) {
	}

	public record LastCrawl(OffsetDateTime finishedAt, boolean stale) {
	}
}
