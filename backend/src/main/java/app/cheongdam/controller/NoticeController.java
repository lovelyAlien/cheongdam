package app.cheongdam.controller;

import app.cheongdam.dto.NoticeDetailResponse;
import app.cheongdam.dto.NoticeFilter;
import app.cheongdam.dto.NoticeListResponse;
import app.cheongdam.service.NoticeCalendarService;
import app.cheongdam.service.NoticeDetailService;
import app.cheongdam.service.NoticeService;
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
public class NoticeController {

	private final NoticeService noticeService;
	private final NoticeDetailService detailService;
	private final NoticeCalendarService calendarService;

	public NoticeController(NoticeService noticeService, NoticeDetailService detailService,
			NoticeCalendarService calendarService) {
		this.noticeService = noticeService;
		this.detailService = detailService;
		this.calendarService = calendarService;
	}

	@GetMapping("/{no}")
	NoticeDetailResponse detail(@PathVariable String no) {
		return detailService.find(no);
	}

	@GetMapping(value = "/{no}/calendar.ics", produces = "text/calendar;charset=UTF-8")
	ResponseEntity<String> calendar(@PathVariable String no) {
		String ics = calendarService.buildIcs(no);
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
		return noticeService.list(filter);
	}
}
