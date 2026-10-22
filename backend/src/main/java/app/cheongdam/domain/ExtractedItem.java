package app.cheongdam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 공고문에서 뽑은 요건 항목. 분류 6은 제약 코드를 갖는다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "extracted_item")
public class ExtractedItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String houseManageNo;

	private int version;

	/** 1~6 (DATA 2장 분류) */
	@JdbcTypeCode(SqlTypes.SMALLINT)
	private int category;

	/** 분류 6일 때만 */
	private String constraintCode;

	/** '전체' 또는 공급 유형 코드 */
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(columnDefinition = "text[]")
	private List<String> targets;

	private String requirement;

	/** 소득·자산 기준 표(선택) */
	@JdbcTypeCode(SqlTypes.JSON)
	private String criteriaTable;

	private boolean isGeneralRule;

	/** confirmed / needs_review (P-05) */
	private String status;

	private int sortOrder;
}
