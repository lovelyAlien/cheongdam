package app.cheongdam.service;

import app.cheongdam.dto.NoticeDetailResponse;
import app.cheongdam.exception.ApiException;
import app.cheongdam.util.NoticeCalendar;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** S-03·메일의 `캘린더에 추가`. 상세의 일정에서 남은 단계만 .ics로 만든다. */
@Service
public class NoticeCalendarService {

	private final NoticeDetailService detailService;
	private final Clock clock;
	private final String baseUrl;

	public NoticeCalendarService(NoticeDetailService detailService, Clock clock,
			@Value("${cheongdam.base-url:https://cheongdam.app}") String baseUrl) {
		this.detailService = detailService;
		this.clock = clock;
		this.baseUrl = baseUrl;
	}

	/** @throws ApiException 남은 일정이 없으면 404(화면은 마감 공고에서 버튼을 숨긴다) */
	public String buildIcs(String no) {
		NoticeDetailResponse d = detailService.find(no);
		List<NoticeCalendar.Stage> stages = d.schedule().stream()
				.map(r -> new NoticeCalendar.Stage(r.stage(), r.area(), r.startOn(), r.endOn()))
				.toList();
		String ics = NoticeCalendar.build(no, d.header().name(), d.header().noticeUrl(), baseUrl, stages,
				LocalDate.now(clock), Instant.now(clock));
		if (ics == null) {
			throw ApiException.notFound("추가할 일정이 없어요.");
		}
		return ics;
	}
}
