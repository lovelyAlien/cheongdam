package app.cheongdam.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 공고 버전당 한 번의 추출 실행(재시도는 attempt 증가). */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "extraction_run")
public class ExtractionRun {

	@EmbeddedId
	private NoticeVersionId id;

	/** pending / running / done / partial / failed */
	private String status;

	/** 최대 3회(P-13) */
	private int attempt;

	private OffsetDateTime nextAttemptAt;

	private String model;

	private String promptVersion;

	private Integer pageCount;

	private Integer inputTokens;

	private Integer outputTokens;

	private BigDecimal costUsd;

	/** '확인 필요' 비율. 0.30 초과 시 failed(P-05) */
	private BigDecimal needsReviewRatio;

	private String error;

	private OffsetDateTime startedAt;

	private OffsetDateTime finishedAt;
}
