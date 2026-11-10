package app.cheongdam.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.cheongdam.domain.CrawlRun;
import app.cheongdam.repository.CrawlRunRepository;
import app.cheongdam.service.AlertDispatchService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 적재 시작 시각(P-17)과 수집을 기다리는 한계(ALERT_DELIVERY 7장).
 * 발송(sendDue)은 수집 상태와 무관하게 매 틱 돌아야 한다.
 */
class AlertDispatchJobTest {

	private static final String EIGHT_AM = "2026-10-22T08:00:00+09:00";

	@Test
	void 수집이_돌고_있으면_끝날_때까지_기다린다() {
		AlertDispatchService service = mock(AlertDispatchService.class);
		job(EIGHT_AM, "2026-10-22T07:50:00+09:00", service).tick();

		verify(service, never()).enqueue(any());
		verify(service).sendDue(OffsetDateTime.parse(EIGHT_AM));
	}

	@Test
	void 세_시간_넘게_running인_수집은_죽은_것으로_보고_쌓는다() {
		AlertDispatchService service = mock(AlertDispatchService.class);
		job(EIGHT_AM, "2026-10-22T04:30:00+09:00", service).tick();

		verify(service).enqueue(OffsetDateTime.parse(EIGHT_AM));
	}

	@Test
	void 마감_시각이_지나면_수집이_돌고_있어도_쌓는다() {
		AlertDispatchService service = mock(AlertDispatchService.class);
		String halfPastNine = "2026-10-22T09:30:00+09:00";
		job(halfPastNine, "2026-10-22T09:00:00+09:00", service).tick();

		verify(service).enqueue(OffsetDateTime.parse(halfPastNine));
	}

	@Test
	void 일곱_시_반_전에는_쌓지_않고_발송만_한다() {
		AlertDispatchService service = mock(AlertDispatchService.class);
		String sevenAm = "2026-10-22T07:00:00+09:00";
		job(sevenAm, null, service).tick();

		verify(service, never()).enqueue(any());
		verify(service).sendDue(OffsetDateTime.parse(sevenAm));
	}

	@Test
	void 하루에_한_번만_쌓고_발송은_매_틱_돈다() {
		AlertDispatchService service = mock(AlertDispatchService.class);
		AlertDispatchJob job = job(EIGHT_AM, null, service);
		job.tick();
		job.tick();

		verify(service, times(1)).enqueue(OffsetDateTime.parse(EIGHT_AM));
		verify(service, times(2)).sendDue(OffsetDateTime.parse(EIGHT_AM));
	}

	// ---- 도우미 ----

	private static AlertDispatchJob job(String nowKst, String runningSince, AlertDispatchService service) {
		// 바깥 when(...) 안에서 또 스텁하면 Mockito가 미완성 스텁으로 본다. 먼저 만들어 둔다
		Optional<CrawlRun> run = running(runningSince);
		CrawlRunRepository crawlRuns = mock(CrawlRunRepository.class);
		when(crawlRuns.findFirstByStatusOrderByStartedAtDesc("running")).thenReturn(run);
		Clock clock = Clock.fixed(OffsetDateTime.parse(nowKst).toInstant(), ClockConfig.KST);
		return new AlertDispatchJob(service, crawlRuns, clock);
	}

	private static Optional<CrawlRun> running(String startedAt) {
		if (startedAt == null) {
			return Optional.empty();
		}
		CrawlRun run = mock(CrawlRun.class);
		when(run.getStartedAt()).thenReturn(OffsetDateTime.parse(startedAt));
		return Optional.of(run);
	}
}
