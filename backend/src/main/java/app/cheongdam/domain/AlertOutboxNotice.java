package app.cheongdam.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 한 통에 담을 공고. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "alert_outbox_notice")
public class AlertOutboxNotice {

	@EmbeddedId
	private AlertOutboxNoticeId id;

	@MapsId("outboxId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "outbox_id")
	private AlertOutbox outbox;

	@MapsId("houseManageNo")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "house_manage_no")
	private Notice notice;
}
