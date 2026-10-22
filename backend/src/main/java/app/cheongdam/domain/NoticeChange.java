package app.cheongdam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 정정 전후 값(블록 D / M-03). notice_version에 복합키로 매달리지만 조회는 공고 번호로 한다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notice_change")
public class NoticeChange {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String houseManageNo;

	/** 바뀐 뒤의 버전 */
	private int version;

	private String field;

	private String beforeValue;

	private String afterValue;

	/** api_diff / pdf_notice_box */
	private String source;
}
