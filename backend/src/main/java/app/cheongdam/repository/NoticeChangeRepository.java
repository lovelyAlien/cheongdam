package app.cheongdam.repository;

import app.cheongdam.domain.NoticeChange;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeChangeRepository extends JpaRepository<NoticeChange, Long> {

	List<NoticeChange> findByHouseManageNoOrderByVersionDescIdAsc(String houseManageNo);
}
