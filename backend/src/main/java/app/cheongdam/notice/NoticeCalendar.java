package app.cheongdam.notice;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** DATA.md 1장의 캘린더 이벤트를 .ics(iCalendar) 텍스트로 만든다. 이미 지난 일정은 넣지 않는다. */
final class NoticeCalendar {

	record Stage(String stage, String area, LocalDate startOn, LocalDate endOn) {
	}

	private static final Map<String, String> STAGE_TITLE = Map.of(
			"special", "특별공급 접수", "rank1", "1순위 접수", "rank2", "2순위 접수", "no_rank", "무순위 접수",
			"general", "일반공급 접수", "winner", "당첨자 발표", "contract", "계약");
	private static final Map<String, String> AREA_LABEL = Map.of(
			"local", "해당지역", "gyeonggi", "기타경기", "other", "기타지역");
	private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
			.withZone(ZoneOffset.UTC);

	private NoticeCalendar() {
	}

	/** @return 남은 일정이 없으면 null */
	static String build(String no, String name, String noticeUrl, String baseUrl, List<Stage> stages, LocalDate today,
			Instant now) {
		List<Stage> upcoming = stages.stream().filter(s -> !s.endOn().isBefore(today)).toList();
		if (upcoming.isEmpty()) {
			return null;
		}
		List<String> lines = new ArrayList<>();
		lines.add("BEGIN:VCALENDAR");
		lines.add("VERSION:2.0");
		lines.add("PRODID:-//청담//cheongdam.app//KO");
		lines.add("CALSCALE:GREGORIAN");
		lines.add("METHOD:PUBLISH");
		for (Stage s : upcoming) {
			String area = AREA_LABEL.get(s.area());
			String title = "[청담] " + name + " " + STAGE_TITLE.getOrDefault(s.stage(), s.stage())
					+ (area == null ? "" : " (" + area + ")");
			String description = "청담 상세: " + baseUrl + "/notices/" + no + "\\n청약홈 공고: " + noticeUrl
					+ "\\n일정은 정정될 수 있어요. 최신 일정은 청담이나 청약홈에서 확인하세요.";
			lines.add("BEGIN:VEVENT");
			// 같은 UID로 다시 추가하면 캘린더 앱이 같은 일정으로 보고 덮어쓴다(E-19)
			lines.add("UID:" + no + "-" + s.stage() + "-" + s.area() + "@cheongdam.app");
			lines.add("DTSTAMP:" + STAMP.format(now));
			lines.add("DTSTART;VALUE=DATE:" + DATE.format(s.startOn()));
			lines.add("DTEND;VALUE=DATE:" + DATE.format(s.endOn().plusDays(1))); // 종일 일정의 종료일은 배타
			lines.add("SUMMARY:" + escape(title));
			lines.add("DESCRIPTION:" + description.replace(",", "\\,").replace(";", "\\;"));
			lines.add("URL:" + noticeUrl);
			lines.add("BEGIN:VALARM"); // 하루 전 오전 9시
			lines.add("ACTION:DISPLAY");
			lines.add("DESCRIPTION:" + escape(title));
			lines.add("TRIGGER:-PT15H");
			lines.add("END:VALARM");
			lines.add("END:VEVENT");
		}
		lines.add("END:VCALENDAR");
		StringBuilder out = new StringBuilder();
		for (String line : lines) {
			out.append(fold(line)).append("\r\n");
		}
		return out.toString();
	}

	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n");
	}

	/** RFC 5545: 한 줄은 75바이트까지. 넘으면 CRLF + 공백으로 이어 쓴다. 글자 중간에서 자르지 않는다. */
	static String fold(String line) {
		StringBuilder out = new StringBuilder();
		int bytes = 0;
		for (int i = 0; i < line.length(); ) {
			int cp = line.codePointAt(i);
			String ch = new String(Character.toChars(cp));
			int len = ch.getBytes(StandardCharsets.UTF_8).length;
			if (bytes + len > 75) {
				out.append("\r\n ");
				bytes = 1;
			}
			out.append(ch);
			bytes += len;
			i += Character.charCount(cp);
		}
		return out.toString();
	}
}
