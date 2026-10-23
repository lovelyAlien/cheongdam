package app.cheongdam.mail;

import app.cheongdam.mail.AlertMailRenderer.RenderedMail;
import app.cheongdam.mail.DigestMail.Change;
import app.cheongdam.mail.DigestMail.CorrectionEntry;
import app.cheongdam.mail.DigestMail.NewEntry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 개발 중 메일 모양을 브라우저로 보는 미리보기. 예: {@code /api/dev/mail/new}, {@code /api/dev/mail/combined?format=text}.
 * 종류: new, new-many, correction, combined, confirm, unsubscribed, unsubscribed-all, manage-link. dev 프로파일에서만 뜬다.
 */
@RestController
@Profile("dev")
@RequestMapping("/api/dev/mail")
class DevMailPreviewController {

	private static final Map<String, Object> CONDITIONS = Map.of("v", 1, "region", List.of("서울", "경기"), "supply",
			List.of("newlywed"), "price_max", 50000);

	private final AlertMailRenderer renderer;
	private final Clock clock;

	DevMailPreviewController(AlertMailRenderer renderer, Clock clock) {
		this.renderer = renderer;
		this.clock = clock;
	}

	@GetMapping("/{kind}")
	ResponseEntity<String> preview(@PathVariable String kind, @RequestParam(defaultValue = "html") String format) {
		RenderedMail mail = switch (kind) {
			case "new" -> renderer.digest(digest(2, false, 0));
			case "new-many" -> renderer.digest(digest(10, false, 5));
			case "correction" -> renderer.digest(digest(0, true, 0));
			case "combined" -> renderer.digest(digest(2, true, 0));
			case "confirm" -> renderer.confirmation("sample-confirm-token", CONDITIONS);
			case "unsubscribed" -> renderer.unsubscribed(List.of(CONDITIONS), false);
			case "unsubscribed-all" -> renderer.unsubscribed(List.of(CONDITIONS), true);
			case "manage-link" -> renderer.manageLink("sample-manage-token");
			default -> null;
		};
		if (mail == null) {
			return ResponseEntity.notFound().build();
		}
		boolean text = format.equals("text");
		return ResponseEntity.ok()
				.contentType(text ? MediaType.parseMediaType("text/plain;charset=UTF-8")
						: MediaType.parseMediaType("text/html;charset=UTF-8"))
				.header("X-Mail-Subject", java.net.URLEncoder.encode(mail.subject(), java.nio.charset.StandardCharsets.UTF_8))
				.body(text ? "제목: " + mail.subject() + "\n\n" + mail.text() : mail.html());
	}

	private DigestMail digest(int newCount, boolean withCorrection, int more) {
		LocalDate today = LocalDate.now(clock);
		List<NewEntry> news = new ArrayList<>();
		for (int i = 0; i < newCount; i++) {
			news.add(new NewEntry("DEV00" + (i + 1), i == 0 ? "○○자이 더 센트럴" : "△△ 행복주택 " + (i + 1) + "단지", "general",
					i == 0 ? "private" : "public", "경기", "경기도 수원시 장안구 ○○동 " + (100 + i),
					i == 0 ? "upcoming" : "open", today.plusDays(2 + i), today.plusDays(5 + i), today.plusDays(2 + i),
					i == 0 ? "special" : "rank1", 32100 + i * 1000, 52340 + i * 1000, 59.97, 84.89));
		}
		List<CorrectionEntry> corrections = withCorrection
				? List.of(new CorrectionEntry("DEV001", "○○자이 더 센트럴",
						List.of(new Change("1순위 접수일", today.plusDays(1).toString(), today.plusDays(3).toString()),
								new Change("입주 예정월", "2029-03", "2029-04"))))
				: List.of();
		String subject = withCorrection
				? newCount > 0 ? "[청담] 정정 공고 1건·새 공고 " + newCount + "건" : "[청담] 정정 공고 1건 · ○○자이 더 센트럴"
				: "[청담] 새 공고 " + (newCount + more) + "건 · 서울·경기 신혼부부";
		return new DigestMail(subject, CONDITIONS, "서울·경기 신혼부부 5억 이하", corrections, news, more, "sample-manage-token");
	}
}
