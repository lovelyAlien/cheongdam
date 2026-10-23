package app.cheongdam.config;

import app.cheongdam.repository.CrawlRunRepository;
import app.cheongdam.service.AlertDispatchService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 알림 발송 시각(P-17): 매일 07:30 이후 수집이 끝나 있으면 대상을 쌓고, 10분마다 보낼 때가 된 메일을 보낸다.
 * 06:00 수집이 아직 돌고 있으면 끝난 뒤 첫 틱에 쌓는다. 18:00 수집분은 다음 날 아침에 나간다.
 * 실제 메일 발송기가 연결되기 전에는 꺼 둔다: {@code app.alerts.dispatch.enabled=true}(dev 프로파일은 켜짐).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.alerts.dispatch.enabled", havingValue = "true")
class AlertDispatchJob {

	static final LocalTime FROM = LocalTime.of(7, 30);

	private final AlertDispatchService service;
	private final CrawlRunRepository crawlRuns;
	private final Clock clock;
	/** shortcut: 프로세스 메모리. 재시작하면 하루 한 번 더 매칭할 수 있지만 결과는 같다(구독당 하루 한 통). */
	private volatile LocalDate enqueuedOn;

	AlertDispatchJob(AlertDispatchService service, CrawlRunRepository crawlRuns, Clock clock) {
		this.service = service;
		this.crawlRuns = crawlRuns;
		this.clock = clock;
	}

	@Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
	void tick() {
		OffsetDateTime now = OffsetDateTime.now(clock);
		LocalDate today = now.toLocalDate();
		if (!today.equals(enqueuedOn) && !now.toLocalTime().isBefore(FROM) && !crawlRuns.existsByStatus("running")) {
			service.enqueue(now);
			enqueuedOn = today;
		}
		service.sendDue(now);
	}
}
