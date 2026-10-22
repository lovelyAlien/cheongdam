package app.cheongdam.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 주택형. 특별공급 유형별 세대수는 APT만 0 이상이고 무순위·재공급은 null이다(P-04). */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notice_unit")
public class NoticeUnit {

	@EmbeddedId
	private NoticeUnitId id;

	@MapsId("houseManageNo")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "house_manage_no")
	private Notice notice;

	private String houseTy;

	private BigDecimal exclusiveArea;

	private BigDecimal supplyArea;

	/** 만원. 0·빈 값은 null(E-06) */
	private Integer topPriceMan;

	private Integer generalCount;

	private Integer specialCount;

	private Integer spNewlywed;

	private Integer spFirstHome;

	private Integer spMultiChild;

	private Integer spNewborn;

	private Integer spElderly;

	private Integer spYouth;

	private Integer spInstitution;

	private Integer spEtc;

	@JdbcTypeCode(SqlTypes.JSON)
	private String raw;

	public String getModelNo() {
		return id.getModelNo();
	}
}
