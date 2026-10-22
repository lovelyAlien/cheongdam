package app.cheongdam.repository;

import app.cheongdam.domain.NoticeVersion;
import app.cheongdam.domain.NoticeVersionId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeVersionRepository extends JpaRepository<NoticeVersion, NoticeVersionId> {

	/** 정정으로 생긴 버전(2 이상)을 최신순으로. */
	List<NoticeVersion> findByIdHouseManageNoAndIdVersionGreaterThanOrderByIdVersionDesc(String houseManageNo,
			int version);
}
