package app.cheongdam.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode
@Embeddable
public class AlertOutboxNoticeId implements Serializable {

	private Long outboxId;

	private String houseManageNo;

	private int version;

	/** new / correction */
	private String kind;
}
