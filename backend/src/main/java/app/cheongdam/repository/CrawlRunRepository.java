package app.cheongdam.repository;

import app.cheongdam.domain.CrawlRun;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlRunRepository extends JpaRepository<CrawlRun, Long> {

	/** 가장 최근에 시작된 수집 실행. 알림 적재가 수집을 기다릴지 판단한다(ALERT_DELIVERY 7장). */
	Optional<CrawlRun> findFirstByStatusOrderByStartedAtDesc(String status);

	Optional<CrawlRun> findFirstByStatusAndFinishedAtIsNotNullOrderByFinishedAtDesc(String status);
}
