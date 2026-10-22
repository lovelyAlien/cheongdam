package app.cheongdam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 공고. APT·무순위 번호 대역이 겹치지 않아 한 테이블에 둔다. 수집은 Python(A)이 쓰고 API(B)는 읽는다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notice")
public class Notice {

	/** HOUSE_MANAGE_NO (= PBLANC_NO) */
	@Id
	private String houseManageNo;

	/** general / no_rank / resupply (P-02) */
	private String noticeType;

	/** apt / remndr */
	private String sourceApi;

	private String name;

	private String region;

	private String regionCode;

	private String address;

	/** private / public. 무순위는 null */
	private String houseKind;

	private String developer;

	private LocalDate announcedOn;

	/** 둘 다 null이면 '일정 미정'(E-05) */
	private LocalDate rceptStart;

	private LocalDate rceptEnd;

	private LocalDate winnerOn;

	private LocalDate contractStart;

	private LocalDate contractEnd;

	/** yyyyMM */
	private String moveInYm;

	private Integer totalHouseholds;

	private String noticeUrl;

	private String homepageUrl;

	private String phone;

	private Boolean isRegulated;

	/** API 응답 원문(공고) */
	@JdbcTypeCode(SqlTypes.JSON)
	private String raw;

	private String rawHash;

	private int currentVersion;

	private OffsetDateTime firstCollectedAt;

	private OffsetDateTime lastCheckedAt;

	private OffsetDateTime lastChangedAt;

	/** 청약홈에서 내려간 공고(P-16) */
	private OffsetDateTime removedAt;

	/** pending / done / partial / failed (P-06) */
	private String summaryStatus;

	private OffsetDateTime createdAt;

	private OffsetDateTime updatedAt;
}
