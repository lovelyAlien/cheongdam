package app.cheongdam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 발송 대기 메일. 구독당 하루 한 통(P-19). */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "alert_outbox")
public class AlertOutbox {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "subscription_id")
	private Subscription subscription;

	private LocalDate sendDate;

	/** new / correction / combined */
	private String mailType;

	/** pending / sent / failed */
	private String status;

	private int attempts;

	private OffsetDateTime nextAttemptAt;

	private String error;

	private OffsetDateTime createdAt;

	private OffsetDateTime sentAt;
}
