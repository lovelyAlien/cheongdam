package app.cheongdam.repository;

import app.cheongdam.domain.NoticeUnit;
import app.cheongdam.domain.NoticeUnitId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeUnitRepository extends JpaRepository<NoticeUnit, NoticeUnitId> {

	List<NoticeUnit> findByIdHouseManageNoOrderByExclusiveAreaAscIdModelNoAsc(String houseManageNo);

	List<NoticeUnit> findByIdHouseManageNoIn(Collection<String> houseManageNos);
}
