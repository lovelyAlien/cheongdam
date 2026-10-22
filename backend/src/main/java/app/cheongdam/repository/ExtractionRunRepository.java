package app.cheongdam.repository;

import app.cheongdam.domain.ExtractionRun;
import app.cheongdam.domain.NoticeVersionId;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtractionRunRepository extends JpaRepository<ExtractionRun, NoticeVersionId> {

	/** 추출이 끝난(done·partial) 가장 최근 버전. 없으면 empty. */
	@Query("""
			select max(r.id.version) from ExtractionRun r
			where r.id.houseManageNo = :no and r.status in ('done', 'partial')
			""")
	Optional<Integer> findLatestFinishedVersion(@Param("no") String houseManageNo);
}
