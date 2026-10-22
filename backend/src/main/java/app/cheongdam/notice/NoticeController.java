package app.cheongdam.notice;

import app.cheongdam.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notices")
class NoticeController {

	private final NoticeRepository repository;
	private final NoticeDetailRepository detailRepository;
	private final Clock clock;
	private final String baseUrl;

	NoticeController(NoticeRepository repository, NoticeDetailRepository detailRepository, Clock clock,
			@Value("${cheongdam.base-url:https://cheongdam.app}") String baseUrl) {
		this.repository = repository;
		this.detailRepository = detailRepository;
		this.clock = clock;
		this.baseUrl = baseUrl;
	}

	@GetMapping("/{no}")
	NoticeDetailResponse detail(@PathVariable String no) {
		return detailRepository.find(no);
	}

	/** S-03·메일의 `캘린더에 추가`. 남은 일정이 없으면 404(화면은 마감 공고에서 버튼을 숨긴다). */
	@GetMapping(value = "/{no}/calendar.ics", produces = "text/calendar;charset=UTF-8")
	ResponseEntity<String> calendar(@PathVariable String no) {
		NoticeDetailResponse d = detailRepository.find(no);
		List<NoticeCalendar.Stage> stages = d.schedule().stream()
				.map(r -> new NoticeCalendar.Stage(r.stage(), r.area(), r.startOn(), r.endOn()))
				.toList();
		String ics = NoticeCalendar.build(no, d.header().name(), d.header().noticeUrl(), baseUrl, stages,
				LocalDate.now(clock), Instant.now(clock));
		if (ics == null) {
			throw ApiException.notFound("추가할 일정이 없어요.");
		}
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename("cheongdam-" + no + ".ics").build().toString())
				.body(ics);
	}

	@GetMapping
	NoticeListResponse list(
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String region,
			@RequestParam(required = false) String supply,
			@RequestParam(name = "price_min", required = false) String priceMin,
			@RequestParam(name = "price_max", required = false) String priceMax,
			@RequestParam(required = false) String area,
			@RequestParam(name = "notice_type", required = false) String noticeType,
			@RequestParam(name = "house_kind", required = false) String houseKind,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String limit,
			@RequestParam(required = false) String offset) {
		NoticeFilter filter = NoticeFilter.parse(q, status, region, supply, priceMin, priceMax, area, noticeType,
				houseKind, sort, limit, offset);
		return repository.list(filter);
	}
}
