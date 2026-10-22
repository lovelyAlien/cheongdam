package app.cheongdam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 수집 실행 이력. 마지막 success의 finished_at이 24시간을 넘으면 S-01이 경고한다(P-15). */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "crawl_run")
public class CrawlRun {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private OffsetDateTime startedAt;

	private OffsetDateTime finishedAt;

	/** running / success / failed */
	private String status;

	private int noticeSeen;

	private int noticeNew;

	private int noticeChanged;

	private String error;
}
