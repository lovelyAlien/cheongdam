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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 알림 구독. 해지·미확인·반송 처리 뒤에는 이메일을 지운다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "subscription")
public class Subscription {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 소문자 정규화 */
	private String email;

	/** S-01 쿼리와 같은 형식. 접수 상태·검색어 제외 */
	@JdbcTypeCode(SqlTypes.JSON)
	private String conditions;

	private String conditionsHash;

	/** pending / active / unsubscribed / suspended */
	private String status;

	private String confirmToken;

	private String manageToken;

	private OffsetDateTime consentAt;

	private OffsetDateTime requestedAt;

	private OffsetDateTime confirmedAt;

	private OffsetDateTime unsubscribedAt;

	private OffsetDateTime lastSentAt;
}
