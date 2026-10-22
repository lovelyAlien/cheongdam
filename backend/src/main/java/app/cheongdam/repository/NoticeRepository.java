package app.cheongdam.repository;

import app.cheongdam.domain.Notice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** 목록의 동적 조건은 {@link NoticeSpecifications}로 조립해 findAll(spec, pageable)에 넘긴다. */
public interface NoticeRepository extends JpaRepository<Notice, String>, JpaSpecificationExecutor<Notice> {
}
