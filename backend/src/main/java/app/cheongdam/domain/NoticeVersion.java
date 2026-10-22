package app.cheongdam.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 공고 버전. 정정이 감지될 때마다 한 줄씩 늘어난다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notice_version")
public class NoticeVersion {

	@EmbeddedId
	private NoticeVersionId id;

	@MapsId("houseManageNo")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "house_manage_no")
	private Notice notice;

	private OffsetDateTime detectedAt;

	private String apiHash;

	private String pdfHash;

	private String pdfFileName;

	private String pdfPath;

	/** pending / ok / none / not_pdf / failed */
	private String pdfStatus;

	/** api / pdf / both. version=1은 null */
	private String changeSource;

	public int getVersion() {
		return id.getVersion();
	}
}
