package app.cheongdam.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 단계별 접수·일정. 캘린더 이벤트와 S-03 타임라인의 원천. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notice_schedule")
public class NoticeSchedule {

	@EmbeddedId
	private NoticeScheduleId id;

	@MapsId("houseManageNo")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "house_manage_no")
	private Notice notice;

	private LocalDate startOn;

	private LocalDate endOn;

	public String getStage() {
		return id.getStage();
	}

	public String getArea() {
		return id.getArea();
	}
}
