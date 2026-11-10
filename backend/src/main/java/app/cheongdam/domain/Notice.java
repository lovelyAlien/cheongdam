package app.cheongdam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
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

	/** general / no_rank / resupply / public_sale / public_rent (P-02, D-30) */
	private String noticeType;

	/** apt / remndr */
	private String sourceApi;

	private String name;

	/** 표시용. 여러 시도에 걸친 공고는 '전국' 또는 'A 외 N' */
	private String region;

	/** 공고가 걸친 시도 전부(지역 필터용). 배치가 늘 채운다. 테스트 데이터는 null일 수 있다 */
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(columnDefinition = "text[]")
	private List<String> regions;

	private String regionCode;

	private String address;

	/** 주소 → 좌표. geoAccuracy: exact / district / none, 변환 전이면 null (P-21) */
	private Double lat;

	private Double lng;

	private String geoAccuracy;

	/** 좌표를 만든 때의 주소 */
	private String geoAddress;

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

	/** 공급유형(행복주택, 국민임대 등). 마이홈 공공임대만 값이 있다 */
	private String supplyKind;

	/** 마이홈 정정 공고가 가리키는 이전 공고 번호 */
	private String beforeNoticeNo;

	/** 이 공고를 대체한 정정 공고 번호. 값이 있으면 목록·알림에서 뺀다(E-23) */
	private String successorNo;

	private OffsetDateTime createdAt;

	private OffsetDateTime updatedAt;
}
