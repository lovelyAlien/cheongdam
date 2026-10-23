package app.cheongdam.mail;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** M-02(새 공고)·M-03(정정)·합본에 담을 내용. HTML 렌더링은 실제 발송기가 맡는다. */
public record DigestMail(
		String subject,
		/** 구독 조건 v1. `N건 더 보기` 링크와 알림 설정 링크를 만들 때 쓴다 */
		Map<String, Object> conditions,
		String conditionsSummary,
		/** 정정이 위, 새 공고가 아래(P-19) */
		List<CorrectionEntry> corrections,
		/** 첫 접수일 가까운 순, 최대 10건. 넘는 건수는 moreNewCount */
		List<NewEntry> newNotices,
		int moreNewCount,
		String manageToken) {

	public record NewEntry(
			String houseManageNo,
			String name,
			String noticeType,
			String houseKind,
			String region,
			String address,
			String status,
			LocalDate rceptStart,
			LocalDate rceptEnd,
			/** 접수 단계 중 가장 이른 날과 그 단계(special, rank1, no_rank 등). 일정이 없으면 null */
			LocalDate firstRceptOn,
			String firstRceptStage,
			Integer priceMinMan,
			Integer priceMaxMan,
			Double areaMin,
			Double areaMax) {
	}

	public record CorrectionEntry(String houseManageNo, String name, List<Change> changes) {
	}

	public record Change(String field, String before, String after) {
	}
}
