package app.cheongdam.repository;

import app.cheongdam.domain.CrawlRun;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlRunRepository extends JpaRepository<CrawlRun, Long> {

	boolean existsByStatus(String status);

	Optional<CrawlRun> findFirstByStatusAndFinishedAtIsNotNullOrderByFinishedAtDesc(String status);
}
