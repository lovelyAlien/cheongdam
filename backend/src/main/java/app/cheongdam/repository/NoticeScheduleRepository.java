package app.cheongdam.repository;

import app.cheongdam.domain.NoticeSchedule;
import app.cheongdam.domain.NoticeScheduleId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeScheduleRepository extends JpaRepository<NoticeSchedule, NoticeScheduleId> {

	List<NoticeSchedule> findByIdHouseManageNo(String houseManageNo);
}
