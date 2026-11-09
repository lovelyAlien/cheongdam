package app.cheongdam.mail;

import app.cheongdam.service.AlertDispatchService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 매칭·발송을 지금 한 번 돌린다. 스케줄은 07:30 이후 10분 틱이라(P-17) 메일이 실제로 오는지 볼 때 기다릴 수 없다.
 * dev 프로파일에서만 뜬다.
 *
 * <p>샘플 공고는 `이틀 전에 수집된` 것이라 방금 확인한 구독의 매칭 기준 시각보다 앞선다. 그대로 돌리면 한 건도 잡히지 않으므로
 * {@code rewind_days}만큼 구독의 기준 시각을 뒤로 물린 뒤 매칭한다(기본 3일, 0이면 그대로).
 */
@RestController
@Profile("dev")
@RequestMapping("/api/dev/alerts")
class DevAlertDispatchController {

	/** 한 번 보낸 공고는 {@code alert_log}가 막으므로 같은 날 다시 불러도 0통이 맞다(P-19). */
	record DispatchResult(int activeSubscriptions, int rewound, int queued, int sent, int pending, int failed,
			String lastError) {
	}

	private final AlertDispatchService service;
	private final JdbcClient jdbc;
	private final Clock clock;

	DevAlertDispatchController(AlertDispatchService service, JdbcClient jdbc, Clock clock) {
		this.service = service;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@PostMapping("/dispatch")
	DispatchResult dispatch(@RequestParam(name = "rewind_days", defaultValue = "3") int rewindDays) {
		OffsetDateTime now = OffsetDateTime.now(clock);
		int rewound = rewindDays <= 0 ? 0 : rewind(now.minusDays(rewindDays));
		int queued = service.enqueue(now);
		int sent = service.sendDue(now);
		return new DispatchResult(activeSubscriptions(), rewound, queued, sent, todayCount("pending"),
				todayCount("failed"), lastError());
	}

	/** 매칭 기준 시각을 뒤로만 물린다. 앞으로 당기지 않으므로 여러 번 불러도 안전하다. */
	private int rewind(OffsetDateTime to) {
		int moved = jdbc.sql("SELECT count(*) FROM subscription WHERE status = 'active' "
				+ "AND (confirmed_at > :t OR last_sent_at > :t)").param("t", to).query(Integer.class).single();
		jdbc.sql("UPDATE subscription SET last_sent_at = NULL WHERE status = 'active' AND last_sent_at > :t")
				.param("t", to).update();
		jdbc.sql("UPDATE subscription SET confirmed_at = :t WHERE status = 'active' AND confirmed_at > :t")
				.param("t", to).update();
		return moved;
	}

	private int activeSubscriptions() {
		return jdbc.sql("SELECT count(*) FROM subscription WHERE status = 'active'").query(Integer.class).single();
	}

	private int todayCount(String status) {
		return jdbc.sql("SELECT count(*) FROM alert_outbox WHERE send_date = :d AND status = :s")
				.param("d", LocalDate.now(clock)).param("s", status).query(Integer.class).single();
	}

	/** 오늘 대기 행에 남은 마지막 오류. SMTP 인증 실패 같은 설정 문제를 응답에서 바로 본다. */
	private String lastError() {
		return jdbc.sql("SELECT error FROM alert_outbox WHERE send_date = :d AND error IS NOT NULL "
				+ "ORDER BY id DESC LIMIT 1").param("d", LocalDate.now(clock)).query(String.class).optional()
				.orElse(null);
	}
}
