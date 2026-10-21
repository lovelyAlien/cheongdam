package app.cheongdam.notice;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notices")
class NoticeController {

	private final NoticeRepository repository;

	NoticeController(NoticeRepository repository) {
		this.repository = repository;
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
