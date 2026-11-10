package app.cheongdam.dto;

import java.time.LocalDate;
import java.util.List;

/** GET /api/notices/calendar (S-10). 그 달과 겹치는 단계별 일정. 날마다 펼치는 일은 화면이 한다. */
public record NoticeCalendarResponse(String month, List<Event> events) {

	public record Event(String houseManageNo, String name, String stage, String area, LocalDate startOn,
			LocalDate endOn) {
	}
}
