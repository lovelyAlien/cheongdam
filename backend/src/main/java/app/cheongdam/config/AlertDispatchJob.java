package app.cheongdam.config;

import app.cheongdam.domain.CrawlRun;
import app.cheongdam.repository.CrawlRunRepository;
import app.cheongdam.service.AlertDispatchService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 알림 발송 시각(P-17): 매일 07:30 이후 수집이 끝나 있으면 대상을 쌓고, 10분마다 보낼 때가 된 메일을 보낸다.
 * 06:00 수집이 아직 돌고 있으면 끝난 뒤 첫 틱에 쌓는다. 18:00 수집분은 다음 날 아침에 나간다.
 *
 * <p>수집을 무한정 기다리지 않는다(ALERT_DELIVERY 7장). 수집 컨테이너가 죽으면 {@code crawl_run}에 {@code running}
 * 행이 그대로 남아 알림이 영구히 멈추기 때문이다. {@link #STALE_CRAWL}보다 오래 도는 수집은 죽은 것으로 보고,
 * {@link #DEADLINE}이 지나면 수집 상태와 무관하게 쌓는다.
 *
 * <p>실제 메일 발송기가 연결되기 전에는 꺼 둔다: {@code app.alerts.dispatch.enabled=true}(dev 프로파일은 켜짐).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.alerts.dispatch.enabled", havingValue = "true")
class AlertDispatchJob {

	private static final Logger log = LoggerFactory.getLogger(AlertDispatchJob.class);

	static final LocalTime FROM = LocalTime.of(7, 30);
	/** 이 시각이 지나면 수집 상태와 무관하게 쌓는다. */
	static final LocalTime DEADLINE = LocalTime.of(9, 30);
	/** 이보다 오래 {@code running}인 수집은 죽은 것으로 보고 기다리지 않는다. */
	static final Duration STALE_CRAWL = Duration.ofHours(3);

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
		if (!today.equals(enqueuedOn) && !now.toLocalTime().isBefore(FROM)) {
			OffsetDateTime runningSince = crawlRuns.findFirstByStatusOrderByStartedAtDesc("running")
					.map(CrawlRun::getStartedAt).orElse(null);
			if (!waitForCrawl(now, runningSince)) {
				if (runningSince != null) {
					log.warn("수집이 {}부터 running인데 기다리지 않고 알림 대상을 쌓아요. 수집 배치를 확인해 주세요(ALERT_DELIVERY 7장)",
							runningSince);
				}
				service.enqueue(now);
				enqueuedOn = today;
			}
		}
		service.sendDue(now);
	}

	/**
	 * 돌고 있는 수집을 기다릴지. {@code runningSince}가 null이면 기다릴 수집이 없다.
	 * {@link #DEADLINE}이 지났거나 {@link #STALE_CRAWL}보다 오래 돌고 있으면 기다리지 않는다.
	 */
	static boolean waitForCrawl(OffsetDateTime now, OffsetDateTime runningSince) {
		if (runningSince == null || !now.toLocalTime().isBefore(DEADLINE)) {
			return false;
		}
		return !runningSince.isBefore(now.minus(STALE_CRAWL));
	}
}
