package app.cheongdam.util;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** 접수 상태·D-day·일정 단계 상태(P-01). 목록은 같은 규칙을 NoticeSpecifications에서 조건식으로 계산한다. */
public final class NoticeStatus {

	private NoticeStatus() {
	}

	/** 시작·종료일 중 하나만 있으면 같은 날로 본다. 둘 다 없으면 unknown(E-05). */
	public static String of(LocalDate start, LocalDate end, LocalDate today) {
		LocalDate s = start != null ? start : end;
		LocalDate e = end != null ? end : start;
		if (s == null) {
			return "unknown";
		}
		if (today.isBefore(s)) {
			return "upcoming";
		}
		return today.isAfter(e) ? "closed" : "open";
	}

	public static Integer dDay(String status, LocalDate start, LocalDate end, LocalDate today) {
		return switch (status) {
			case "upcoming" -> (int) ChronoUnit.DAYS.between(today, start);
			case "open" -> (int) ChronoUnit.DAYS.between(today, end);
			default -> null;
		};
	}

	/** 타임라인 단계: done(지남) / today(오늘이 기간 안) / upcoming. */
	public static String stageState(LocalDate start, LocalDate end, LocalDate today) {
		if (end.isBefore(today)) {
			return "done";
		}
		return start.isAfter(today) ? "upcoming" : "today";
	}
}
